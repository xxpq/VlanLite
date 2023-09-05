package com.matrix.hiper.lite.utils;

import android.util.Log;

public final class LogUtils {
    // 定义DEBUG，用于控制log是否允许被打印
    public static final boolean DEBUG = true;
    private static final String TAG = "VlanLite";

    /**
     * log分等级
     * 等级越高，打印出的信息越少
     * 等级越低，打印出的信息越多
     */

    // 定义几种方法，用于输出对应log
    public static void d(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    public static void w(String message) {
        if (DEBUG) {
            Log.w(TAG, message);
        }
    }

    public static void e(String message) {
        if (DEBUG) {
            Log.e(TAG, message);
        }
    }

    public static void i(String message) {
        if (DEBUG) {
            Log.i(TAG, message);
        }
    }
}