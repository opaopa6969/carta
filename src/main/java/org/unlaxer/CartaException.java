package org.unlaxer;

public class CartaException extends RuntimeException {
    private final String code;

    public CartaException(String message) {
        super(message);
        this.code = "CARTA_ERROR";
    }

    public CartaException(String code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public String code() { return code; }
}
