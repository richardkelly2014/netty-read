package io.netty.util.internal;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Created by jiangfei on 2020/5/19.
 */
final class PlatformDependent0 {

    private static final long ADDRESS_FIELD_OFFSET;
    private static final long BYTE_ARRAY_BASE_OFFSET;
    private static final long INT_ARRAY_BASE_OFFSET;
    private static final long INT_ARRAY_INDEX_SCALE;
    private static final long LONG_ARRAY_BASE_OFFSET;
    private static final long LONG_ARRAY_INDEX_SCALE;
    private static final Constructor<?> DIRECT_BUFFER_CONSTRUCTOR;
    private static final Throwable EXPLICIT_NO_UNSAFE_CAUSE = explicitNoUnsafeCause0();
    private static final Method ALLOCATE_ARRAY_METHOD;

    //java 版本
    private static final int JAVA_VERSION = javaVersion0();
    //是否是android
    private static final boolean IS_ANDROID = isAndroid0();

    private static final Throwable UNSAFE_UNAVAILABILITY_CAUSE;
    private static final Object INTERNAL_UNSAFE;

    //是否可以用反射
    private static final boolean IS_EXPLICIT_TRY_REFLECTION_SET_ACCESSIBLE = explicitTryReflectionSetAccessible0();

    static final Unsafe UNSAFE;

    // constants borrowed from murmur3
    static final int HASH_CODE_ASCII_SEED = 0xc2b2ae35;
    static final int HASH_CODE_C1 = 0xcc9e2d51;
    static final int HASH_CODE_C2 = 0x1b873593;

    private static final long UNSAFE_COPY_THRESHOLD = 1024L * 1024L;
    private static final boolean UNALIGNED;

    static {
        //静态代码块
        //nio byteBuffer
        final ByteBuffer direct;
        //地址 field
        Field addressField = null;
        //分配数据方法
        Method allocateArrayMethod = null;
        //unsafe 不可用异常
        Throwable unsafeUnavailabilityCause = null;
        Unsafe unsafe;
        Object internalUnsafe = null;

        if ((unsafeUnavailabilityCause = EXPLICIT_NO_UNSAFE_CAUSE) != null) {
            //不使用unsafe
            direct = null;
            addressField = null;
            unsafe = null;
            internalUnsafe = null;
        } else {
            direct = ByteBuffer.allocateDirect(1);
            final Object maybeUnsafe = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    try {
                        final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                        // We always want to try using Unsafe as the access still works on java9 as well and
                        // we need it for out native-transports and many optimizations.
                        Throwable cause = ReflectionUtil.trySetAccessible(unsafeField, false);
                        if (cause != null) {
                            return cause;
                        }
                        // the unsafe instance
                        return unsafeField.get(null);
                    } catch (NoSuchFieldException e) {
                        return e;
                    } catch (SecurityException e) {
                        return e;
                    } catch (IllegalAccessException e) {
                        return e;
                    } catch (NoClassDefFoundError e) {
                        return e;
                    }
                }
            });

            if (maybeUnsafe instanceof Throwable) {
                //异常
                unsafe = null;
                unsafeUnavailabilityCause = (Throwable) maybeUnsafe;
            } else {
                unsafe = (Unsafe) maybeUnsafe;
            }

            if (unsafe != null) {
                final Unsafe finalUnsafe = unsafe;
                final Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    @Override
                    public Object run() {
                        try {
                            // 内存copy
                            finalUnsafe.getClass().getDeclaredMethod(
                                    "copyMemory", Object.class, long.class, Object.class, long.class, long.class);
                            return null;
                        } catch (NoSuchMethodException e) {
                            return e;
                        } catch (SecurityException e) {
                            return e;
                        }
                    }
                });

                if (maybeException == null) {
                    //copyMemory 可以用
                } else {
                    // Unsafe.copyMemory(Object, long, Object, long, long) unavailable.
                    unsafe = null;
                    unsafeUnavailabilityCause = (Throwable) maybeException;
                }
            }

            if (unsafe != null) {
                final Unsafe finalUnsafe = unsafe;

                // attempt to access field Buffer#address
                final Object maybeAddressField = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    @Override
                    public Object run() {
                        try {
                            //byteBuffer 内存地址
                            final Field field = Buffer.class.getDeclaredField("address");
                            //变量 offset
                            final long offset = finalUnsafe.objectFieldOffset(field);
                            final long address = finalUnsafe.getLong(direct, offset);

                            // if direct really is a direct buffer, address will be non-zero
                            if (address == 0) {
                                return null;
                            }
                            return field;
                        } catch (NoSuchFieldException e) {
                            return e;
                        } catch (SecurityException e) {
                            return e;
                        }
                    }
                });

                if (maybeAddressField instanceof Field) {
                    addressField = (Field) maybeAddressField;
                } else {
                    unsafeUnavailabilityCause = (Throwable) maybeAddressField;
                    unsafe = null;
                }
            }

            if (unsafe != null) {
                long byteArrayIndexScale = unsafe.arrayIndexScale(byte[].class);
                if (byteArrayIndexScale != 1) {
                    unsafeUnavailabilityCause = new UnsupportedOperationException("Unexpected unsafe.arrayIndexScale");
                    unsafe = null;
                }
            }
        }

        UNSAFE_UNAVAILABILITY_CAUSE = unsafeUnavailabilityCause;
        UNSAFE = unsafe;

        if (unsafe == null) {
            ADDRESS_FIELD_OFFSET = -1;
            BYTE_ARRAY_BASE_OFFSET = -1;
            LONG_ARRAY_BASE_OFFSET = -1;
            LONG_ARRAY_INDEX_SCALE = -1;
            INT_ARRAY_BASE_OFFSET = -1;
            INT_ARRAY_INDEX_SCALE = -1;
            UNALIGNED = false;
            DIRECT_BUFFER_CONSTRUCTOR = null;
            ALLOCATE_ARRAY_METHOD = null;
        } else {
            Constructor<?> directBufferConstructor;
            long address = -1;
            try {
                final Object maybeDirectBufferConstructor =
                        AccessController.doPrivileged(new PrivilegedAction<Object>() {
                            @Override
                            public Object run() {
                                try {
                                    // byteBuffer 构造函数
                                    final Constructor<?> constructor =
                                            direct.getClass().getDeclaredConstructor(long.class, int.class);
                                    Throwable cause = ReflectionUtil.trySetAccessible(constructor, true);
                                    if (cause != null) {
                                        return cause;
                                    }
                                    return constructor;
                                } catch (NoSuchMethodException e) {
                                    return e;
                                } catch (SecurityException e) {
                                    return e;
                                }
                            }
                        });

                if (maybeDirectBufferConstructor instanceof Constructor<?>) {
                    //申请一个内存地址
                    address = UNSAFE.allocateMemory(1);
                    // try to use the constructor now
                    try {
                        //通过构造函数创建byteBuffer
                        ((Constructor<?>) maybeDirectBufferConstructor).newInstance(address, 1);
                        directBufferConstructor = (Constructor<?>) maybeDirectBufferConstructor;
                    } catch (InstantiationException e) {
                        directBufferConstructor = null;
                    } catch (IllegalAccessException e) {
                        directBufferConstructor = null;
                    } catch (InvocationTargetException e) {
                        directBufferConstructor = null;
                    }
                } else {
                    directBufferConstructor = null;
                }
            } finally {
                if (address != -1) {
                    UNSAFE.freeMemory(address);
                }
            }
            //byteBuffer 构造函数
            DIRECT_BUFFER_CONSTRUCTOR = directBufferConstructor;
            //byteBuffer address Offset
            ADDRESS_FIELD_OFFSET = objectFieldOffset(addressField);
            //byte[] offset
            BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
            //int[] offset
            INT_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
            //int[] scale
            INT_ARRAY_INDEX_SCALE = UNSAFE.arrayIndexScale(int[].class);
            //long[] offset
            LONG_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
            //long[] scale
            LONG_ARRAY_INDEX_SCALE = UNSAFE.arrayIndexScale(long[].class);

            final boolean unaligned;
            Object maybeUnaligned = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    try {
                        Class<?> bitsClass =
                                Class.forName("java.nio.Bits", false, getSystemClassLoader());
                        int version = javaVersion();
                        if (version >= 9) {
                            // Java9/10 use all lowercase and later versions all uppercase.
                            String fieldName = version >= 11 ? "UNALIGNED" : "unaligned";
                            // On Java9 and later we try to directly access the field as we can do this without
                            // adjust the accessible levels.
                            try {
                                Field unalignedField = bitsClass.getDeclaredField(fieldName);
                                if (unalignedField.getType() == boolean.class) {
                                    long offset = UNSAFE.staticFieldOffset(unalignedField);
                                    Object object = UNSAFE.staticFieldBase(unalignedField);
                                    return UNSAFE.getBoolean(object, offset);
                                }
                                // There is something unexpected stored in the field,
                                // let us fall-back and try to use a reflective method call as last resort.
                            } catch (NoSuchFieldException ignore) {
                                // We did not find the field we expected, move on.
                            }
                        }
                        Method unalignedMethod = bitsClass.getDeclaredMethod("unaligned");
                        Throwable cause = ReflectionUtil.trySetAccessible(unalignedMethod, true);
                        if (cause != null) {
                            return cause;
                        }
                        return unalignedMethod.invoke(null);
                    } catch (NoSuchMethodException e) {
                        return e;
                    } catch (SecurityException e) {
                        return e;
                    } catch (IllegalAccessException e) {
                        return e;
                    } catch (ClassNotFoundException e) {
                        return e;
                    } catch (InvocationTargetException e) {
                        return e;
                    }
                }
            });

            if (maybeUnaligned instanceof Boolean) {
                unaligned = (Boolean) maybeUnaligned;
            } else {
                String arch = SystemPropertyUtil.get("os.arch", "");
                //noinspection DynamicRegexReplaceableByCompiledPattern
                unaligned = arch.matches("^(i[3-6]86|x86(_64)?|x64|amd64)$");
            }

            UNALIGNED = unaligned;

            ALLOCATE_ARRAY_METHOD = allocateArrayMethod;
        }
        INTERNAL_UNSAFE = internalUnsafe;
    }


    static boolean isExplicitNoUnsafe() {
        //false 使用unsafe
        return EXPLICIT_NO_UNSAFE_CAUSE != null;
    }

    private static Throwable explicitNoUnsafeCause0() {
        final boolean noUnsafe = SystemPropertyUtil.getBoolean("io.netty.noUnsafe", false);

        if (noUnsafe) {
            return new UnsupportedOperationException("sun.misc.Unsafe: unavailable (io.netty.noUnsafe)");
        }

        // Legacy properties
        String unsafePropName;
        if (SystemPropertyUtil.contains("io.netty.tryUnsafe")) {
            unsafePropName = "io.netty.tryUnsafe";
        } else {
            unsafePropName = "org.jboss.netty.tryUnsafe";
        }
        if (!SystemPropertyUtil.getBoolean(unsafePropName, true)) {
            String msg = "sun.misc.Unsafe: unavailable (" + unsafePropName + ")";
            return new UnsupportedOperationException(msg);
        }
        return null;
    }


    static long objectFieldOffset(Field field) {
        return UNSAFE.objectFieldOffset(field);
    }


    static ClassLoader getContextClassLoader() {
        if (System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return Thread.currentThread().getContextClassLoader();
                }
            });
        }
    }

    static ClassLoader getSystemClassLoader() {
        if (System.getSecurityManager() == null) {
            return ClassLoader.getSystemClassLoader();
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return ClassLoader.getSystemClassLoader();
                }
            });
        }
    }

    static boolean isAndroid() {
        return IS_ANDROID;
    }

    private static boolean isAndroid0() {
        String vmName = SystemPropertyUtil.get("java.vm.name");
        //android == dalvik
        boolean isAndroid = "Dalvik".equals(vmName);
        return isAndroid;
    }

    private static boolean explicitTryReflectionSetAccessible0() {
        // we disable reflective access
        return SystemPropertyUtil.getBoolean("io.netty.tryReflectionSetAccessible", javaVersion() < 9);
    }

    static boolean isExplicitTryReflectionSetAccessible() {
        return IS_EXPLICIT_TRY_REFLECTION_SET_ACCESSIBLE;
    }

    static int javaVersion() {
        return JAVA_VERSION;
    }

    //java 版本号
    private static int javaVersion0() {
        final int majorVersion;

        if (isAndroid0()) {
            majorVersion = 6;
        } else {
            majorVersion = majorVersionFromJavaSpecificationVersion();
        }
        return majorVersion;
    }

    static int majorVersionFromJavaSpecificationVersion() {
        //java.specification.version java版本信息
        return majorVersion(SystemPropertyUtil.get("java.specification.version", "1.6"));
    }

    //java 主版本号
    static int majorVersion(final String javaSpecVersion) {
        final String[] components = javaSpecVersion.split("\\.");
        final int[] version = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            version[i] = Integer.parseInt(components[i]);
        }

        if (version[0] == 1) {
            assert version[1] >= 6;
            return version[1];
        } else {
            return version[0];
        }
    }

    private PlatformDependent0() {
    }
}
