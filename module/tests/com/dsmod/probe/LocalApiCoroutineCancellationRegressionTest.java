package com.dsmod.probe;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;

/** Verifies the reflected host Job used to cancel a disconnected native Flow. */
public final class LocalApiCoroutineCancellationRegressionTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Method create = Main.class.getDeclaredMethod(
                "newLocalApiCancellationJob", ClassLoader.class, Class.class);
        Method cancel = Main.class.getDeclaredMethod(
                "cancelLocalApiCancellationJob", Object.class);
        create.setAccessible(true);
        cancel.setAccessible(true);

        ClassLoader loader = LocalApiCoroutineCancellationRegressionTest.class.getClassLoader();
        Class<?> contextType = loader.loadClass("n02");
        Class<?> jobType = loader.loadClass("p64");
        Object job = create.invoke(null, loader, contextType);
        require(contextType.isInstance(job) && jobType.isInstance(job),
                "host cancellation Job was not a coroutine context element");
        Method active = jobType.getMethod("d");
        Method cancelled = jobType.getMethod("isCancelled");
        Method failure = jobType.getMethod("u");
        require((Boolean) active.invoke(job) && !(Boolean) cancelled.invoke(job),
                "new host cancellation Job was not active");

        cancel.invoke(null, job);
        require((Boolean) cancelled.invoke(job) && !(Boolean) active.invoke(job),
                "disconnect did not cancel the host coroutine Job");
        CancellationException cause = (CancellationException) failure.invoke(job);
        require(cause != null, "cancelled host Job exposed no cancellation cause");
        System.out.println("LocalApiCoroutineCancellationRegressionTest OK");
    }
}
