package com.example.passwordmanager.viewmodel;

public class Resource<T> {
    public enum Status {
        LOADING,
        SUCCESS,
        ERROR
    }

    private final Status status;
    private final T data;
    private final String message;

    private Resource(Status status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
    }

    public static <T> Resource<T> loading() {
        return new Resource<>(Status.LOADING,  null, null);
    }

    public static  <T> Resource<T> success(T data) {
        return new Resource<>(Status.SUCCESS, data, null);
    }

    public static <T> Resource<T> error(String message) {
        return new Resource<>(Status.ERROR, null, message);
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public Status getStatus() {
        return status;
    }
}
