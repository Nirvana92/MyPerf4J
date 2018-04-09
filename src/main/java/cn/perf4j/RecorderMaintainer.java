package cn.perf4j;

import cn.perf4j.aop.NonProfiler;
import cn.perf4j.util.*;
import cn.perf4j.aop.Profiler;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by LinShunkang on 2018/3/13
 */

/**
 * 该类用于存储及维护所有添加了@Profiler注解的接口对应的Recorder
 * 该类的实现原因：
 * 1.为了保证运行时的性能和简化编程，在程序启动时就把recorderMap和backupRecorderMap初始化完成，可以避免判断，并且通过减小loadFactor来优化Map的get()性能；
 * 2.为了避免影响程序的响应时间，利用roundRobinExecutor定时去对recorderMap和backupRecorderMap进行轮转；
 * 3.为了避免recordProcessor处理时间过长影响roundRobinExecutor的处理逻辑，增加一个backgroundExecutor来定时执行recordProcessor
 */
public final class RecorderMaintainer {

    private static final long MIN_MILL_TIME_SLICE = 10 * 1000L;//10s

    private static final long DEFAULT_MILL_TIME_SLICE = 60 * 1000L;//60s

    private static final long MILL_TIME_SLICE = MyProperties.getLong(PropConstants.MILL_TIME_SLICE, DEFAULT_MILL_TIME_SLICE, MIN_MILL_TIME_SLICE);

    private static final ScheduledThreadPoolExecutor roundRobinExecutor = new ScheduledThreadPoolExecutor(1, ThreadUtils.newThreadFactory("MyPerf4J-RoundRobinExecutor_"), new ThreadPoolExecutor.DiscardPolicy());

    private static final ScheduledThreadPoolExecutor backgroundExecutor = new ScheduledThreadPoolExecutor(1, ThreadUtils.newThreadFactory("MyPerf4J-BackgroundExecutor_"), new ThreadPoolExecutor.DiscardPolicy());

    private static PerfStatsProcessor perfStatsProcessor = AsyncPerfStatsProcessor.getInstance();

    private static final boolean needRunning = MyProperties.isSame(PropConstants.RUNNING_STATUS, PropConstants.RUNNING_STATUS_YES);

    private static final boolean accurateMode = MyProperties.isSame(PropConstants.RECORDER_MODE, PropConstants.RECORDER_MODE_ACCURATE);

    private static volatile long nextTimeSliceEndTime = 0L;

    private static volatile boolean backupRecorderReady = false;

    //为了让recorderMap.get()更加快速，减小loadFactor->减少碰撞的概率->加快get()的执行速度
    private static volatile Map<String, AbstractRecorder> recorderMap = MapUtils.createHashMap(256, 0.2F);

    private static volatile Map<String, AbstractRecorder> backupRecorderMap = MapUtils.createHashMap(256, 0.2F);

    static {
        initRecorderMap();

        if (needRunning) {
            roundRobinExecutor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    long currentMills = System.currentTimeMillis();
                    if (nextTimeSliceEndTime == 0L) {
                        nextTimeSliceEndTime = ((currentMills / MILL_TIME_SLICE) * MILL_TIME_SLICE) + MILL_TIME_SLICE;
                    }

                    //还在当前的时间片里
                    if (nextTimeSliceEndTime > currentMills) {
                        return;
                    }
                    nextTimeSliceEndTime = ((currentMills / MILL_TIME_SLICE) * MILL_TIME_SLICE) + MILL_TIME_SLICE;

                    backupRecorderReady = false;
                    try {
                        for (Map.Entry<String, AbstractRecorder> entry : recorderMap.entrySet()) {
                            AbstractRecorder curRecorder = entry.getValue();
                            if (curRecorder.getStartTime() <= 0L || curRecorder.getStopTime() <= 0L) {
                                curRecorder.setStartTime(currentMills - MILL_TIME_SLICE);
                                curRecorder.setStopTime(currentMills);
                            }
                        }

                        for (Map.Entry<String, AbstractRecorder> entry : backupRecorderMap.entrySet()) {
                            AbstractRecorder backupRecorder = entry.getValue();
                            backupRecorder.resetRecord();
                            backupRecorder.setStartTime(currentMills);
                            backupRecorder.setStopTime(currentMills + MILL_TIME_SLICE);
                        }

                        Map<String, AbstractRecorder> tmpMap = recorderMap;
                        recorderMap = backupRecorderMap;
                        backupRecorderMap = tmpMap;
                        Logger.info("roundRobinExecutor finished!!!!");
                    } catch (Exception e) {
                      Logger.error("RecorderMaintainer.roundRobinExecutor error", e);
                    } finally {
                        backupRecorderReady = true;
                    }
                }
            }, 0, 500, TimeUnit.MILLISECONDS);

            backgroundExecutor.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    if (!backupRecorderReady) {
                        return;
                    }

                    try {
                        AbstractRecorder recorder = null;
                        List<PerfStats> perfStatsList = new ArrayList<>(backupRecorderMap.size());
                        for (Map.Entry<String, AbstractRecorder> entry : backupRecorderMap.entrySet()) {
                            recorder = entry.getValue();
                            perfStatsList.add(PerfStatsCalculator.calPerfStats(recorder));
                        }

                        if (recorder != null) {
                            perfStatsProcessor.process(perfStatsList, recorder.getStartTime(), recorder.getStopTime());
                        }

                        Logger.info("backgroundExecutor finished!!!!");
                    } catch (Exception e) {
                        Logger.error("RecorderMaintainer.backgroundExecutor error", e);
                    } finally {
                        backupRecorderReady = false;
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }


    private static void initRecorderMap() {
        long start = System.currentTimeMillis();
        try {
            URL enumeration = RecorderMaintainer.class.getClassLoader().getResource("");
            if (enumeration == null) {
                return;
            }

            File file = new File(enumeration.getPath());
            if (!file.exists() || !file.isDirectory()) {
                return;
            }

            File[] dirFiles = file.listFiles(new FileFilter() {
                // 自定义过滤规则 如果可以循环(包含子目录) 或则是以.class结尾的文件(编译好的java类文件)
                public boolean accept(File file) {
                    return file.isDirectory() || file.getName().endsWith(".class");
                }
            });

            if (dirFiles == null || dirFiles.length <= 0) {
                return;
            }

            for (int i = 0; i < dirFiles.length; ++i) {
                File f = dirFiles[i];
                processAnnotations(getClasses(f.getName()));
            }
        } catch (Exception e) {
            Logger.error("RecorderMaintainer.initRecorderMap()", e);
        }
        Logger.info("RecorderMaintainer.initRecorderMap() cost:" + (System.currentTimeMillis() - start) + "ms");
    }

    private static Set<Class<?>> getClasses(String packageName) {
        Logger.info("Begin scanning " + packageName + "...");
        ClassScanner handler = new ClassScanner(true, true);
        return handler.getClasses(packageName, true);
    }

    private static void processAnnotations(Set<Class<?>> classSet) {
        long startMills = System.currentTimeMillis();
        for (Class<?> clazz : classSet) {
            try {
                NonProfiler nonProfiler = clazz.getAnnotation(NonProfiler.class);
                if (nonProfiler != null) {
                    continue;
                }

                Profiler classProfiler = clazz.getAnnotation(Profiler.class);
                Method[] methodArray = clazz.getMethods();
                for (int k = 0, length = methodArray.length; k < length; ++k) {
                    Method method = methodArray[k];
                    if (!clazz.equals(method.getDeclaringClass()) || clazz.getName().startsWith("org.springframework")) {
                        continue;
                    }

                    NonProfiler methodNonProfiler = method.getAnnotation(NonProfiler.class);
                    if (methodNonProfiler != null) {
                        continue;
                    }

                    Profiler methodProfiler = method.getAnnotation(Profiler.class);
                    if (methodProfiler == null && (methodProfiler = classProfiler) == null) {
                        continue;
                    }

                    //从性能角度考虑，只用类名+方法名，不去组装方法的参数类型！！！
                    String api = clazz.getSimpleName() + "." + method.getName();
                    recorderMap.put(api, getRecorder(api, methodProfiler));
                    backupRecorderMap.put(api, getRecorder(api, methodProfiler));
                }
            } catch (Throwable throwable) {
                Logger.error("processAnnotations(classSet): " + throwable.getMessage());
            }
        }
        Logger.info("RecorderMaintainer.processAnnotations() cost:" + (System.currentTimeMillis() - startMills) + "ms");
    }

    private static AbstractRecorder getRecorder(String api, Profiler profiler) {
        if (accurateMode) {
            return AccurateRecorder.getInstance(api, profiler.mostTimeThreshold(), profiler.outThresholdCount());
        }
        return RoughRecorder.getInstance(api, profiler.mostTimeThreshold());
    }

    public static AbstractRecorder getRecorder(String api) {
        return recorderMap.get(api);
    }

    public static Map<String, AbstractRecorder> getRecorderMap() {
        return Collections.unmodifiableMap(recorderMap);
    }

}