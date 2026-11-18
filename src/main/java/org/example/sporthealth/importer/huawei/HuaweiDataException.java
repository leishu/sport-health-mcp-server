package org.example.sporthealth.importer.huawei;

/** 华为导出文件结构性错误（不是 JSON、缺关键字段等），消息里已带文件名。 */
public class HuaweiDataException extends RuntimeException {

    public HuaweiDataException(String message) {
        super(message);
    }

    public HuaweiDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
