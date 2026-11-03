package org.example.sporthealth.importer;

public class TcxDataException extends RuntimeException {

    public TcxDataException(String message) {
        super(message);
    }

    public TcxDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
