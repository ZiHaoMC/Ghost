package com.zihaomc.ghost.utils;

import com.zihaomc.ghost.LangUtil;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 统一的日志记录工具类。
 * 能够区分客户端和服务端，以安全的方式输出日志。
 * 在客户端，它会尝试使用 LangUtil 进行翻译。
 * 在服务端，它会打印出语言键 (Key)，以便于调试且不会导致崩溃。
 */
public class LogUtil {

    private static void log(String level, String key, Object... args) {
        String message;
        // 检查当前是否在客户端，以便安全地使用 LangUtil
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            try {
                // 移除了外层的 String.format，因为 LangUtil.translate 已经处理了格式化
                message = LangUtil.translate(key, args);
            } catch (Exception e) {
                // 如果 LangUtil 失败，则回退到打印键名
                message = key + " (Translation failed: " + e.getMessage() + ")";
            }
        } else {
            // 在服务端，LangUtil 不可用。我们只打印键名和参数。
            StringBuilder serverMessage = new StringBuilder(key);
            if (args.length > 0) {
                serverMessage.append(" [");
                for (int i = 0; i < args.length; i++) {
                    serverMessage.append(args[i]);
                    if (i < args.length - 1) {
                        serverMessage.append(", ");
                    }
                }
                serverMessage.append("]");
            }
            message = serverMessage.toString();
        }

        // 为 ERROR 和 WARN 级别使用 System.err
        if ("ERROR".equals(level) || "WARN".equals(level)) {
            System.err.println("[" + level + "] " + message);
        } else {
            System.out.println("[" + level + "] " + message);
        }
    }

    public static void info(String key, Object... args) {
        log("INFO", key, args);
    }

    public static void debug(String key, Object... args) {
        // TODO: 未来可以添加一个配置项来完全禁用 DEBUG 日志
        log("DEBUG", key, args);
    }

    public static void warn(String key, Object... args) {
        log("WARN", key, args);
    }

    public static void error(String key, Object... args) {
        log("ERROR", key, args);
    }
    
    /**
     * 用于打印异常堆栈的特殊方法。
     * @param key 描述错误的语言键
     * @param throwable 异常对象
     * @param args 格式化参数
     */
    public static void printStackTrace(String key, Throwable throwable, Object... args) {
        error(key, args);
        throwable.printStackTrace();
    }
}