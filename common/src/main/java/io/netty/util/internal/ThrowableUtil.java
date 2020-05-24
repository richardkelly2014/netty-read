package io.netty.util.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Created by jiangfei on 2020/5/24.
 */
public final class ThrowableUtil {

    private ThrowableUtil() {
    }

    public static <T extends Throwable> T unknownStackTrace(T cause, Class<?> clazz, String method) {
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement(clazz.getName(), method, null, -1)});
        return cause;
    }

    public static String stackTraceToString(Throwable cause) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream pout = new PrintStream(out);
        cause.printStackTrace(pout);
        pout.flush();
        try {
            return new String(out.toByteArray());
        } finally {
            try {
                out.close();
            } catch (IOException ignore) {
                // ignore as should never happen
            }
        }
    }

    public static boolean haveSuppressed() {
        return PlatformDependent.javaVersion() >= 7;
    }

    public static void addSuppressed(Throwable target, Throwable suppressed) {
        if (!haveSuppressed()) {
            return;
        }
        target.addSuppressed(suppressed);
    }

    public static void addSuppressedAndClear(Throwable target, List<Throwable> suppressed) {
        addSuppressed(target, suppressed);
        suppressed.clear();
    }

    public static void addSuppressed(Throwable target, List<Throwable> suppressed) {
        for (Throwable t : suppressed) {
            addSuppressed(target, t);
        }
    }
}
