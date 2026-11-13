package com.minuu.httpserver;

import java.util.ArrayList;
import java.util.List;

/** A dependency-free test harness: each check is named, counted, and reported. */
public final class TestRunner {

    private final List<String> failures = new ArrayList<>();
    private int passed;

    public void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    public void eq(String name, Object expected, Object actual) {
        check(name + " (expected " + expected + ", got " + actual + ")", expected == null ? actual == null : expected.equals(actual));
    }

    /** Runs a test body and records an unexpected exception as a failure. */
    public void section(String name, ThrowingRunnable body) {
        System.out.println("== " + name + " ==");
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(name + " threw " + t);
            System.out.println("  FAIL " + name + " threw " + t);
        }
    }

    public boolean failed() {
        return !failures.isEmpty();
    }

    public void summary() {
        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("failures:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        TestRunner r = new TestRunner();
        HttpRequestTest.run(r);
        RouterTest.run(r);
        KvStoreTest.run(r);
        JsonTest.run(r);
        ServerIntegrationTest.run(r);
        r.summary();
        if (r.failed()) {
            System.exit(1);
        }
    }
}
