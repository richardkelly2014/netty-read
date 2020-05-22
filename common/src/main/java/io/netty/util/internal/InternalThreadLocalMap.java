package io.netty.util.internal;

import java.util.Arrays;

/**
 * Created by jiangfei on 2020/5/21.
 */
public final class InternalThreadLocalMap extends UnpaddedInternalThreadLocalMap {

    private static final int INDEXED_VARIABLE_TABLE_INITIAL_SIZE = 32;
    // 未设置
    public static final Object UNSET = new Object();

    private InternalThreadLocalMap() {
        super(newIndexedVariableTable());
    }

    // 初始化index数组
    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[INDEXED_VARIABLE_TABLE_INITIAL_SIZE];
        Arrays.fill(array, UNSET);
        return array;
    }

}
