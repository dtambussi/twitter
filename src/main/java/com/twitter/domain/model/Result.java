package com.twitter.domain.model;

import java.util.function.Function;

/**
 * A sealed type representing the outcome of an operation that can either succeed or fail.
 * Used for expected business logic outcomes instead of exceptions.
 *
 * @param <T> the type of the success value
 * @param <E> the type of the error
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

    record Success<T, E>(T value) implements Result<T, E> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public T getOrThrow() {
            return value;
        }

        @Override
        public E errorOrNull() {
            return null;
        }

        @Override
        public <U> Result<U, E> map(Function<T, U> mapper) {
            return new Success<>(mapper.apply(value));
        }

        @Override
        public <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper) {
            return mapper.apply(value);
        }
    }

    record Failure<T, E>(E error) implements Result<T, E> {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T getOrThrow() {
            throw new IllegalStateException("Cannot get value from Failure: " + error);
        }

        @Override
        public E errorOrNull() {
            return error;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U, E> map(Function<T, U> mapper) {
            return (Result<U, E>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper) {
            return (Result<U, E>) this;
        }
    }

    boolean isSuccess();

    boolean isFailure();

    T getOrThrow();

    E errorOrNull();

    <U> Result<U, E> map(Function<T, U> mapper);

    <U> Result<U, E> flatMap(Function<T, Result<U, E>> mapper);

    static <T, E> Result<T, E> success(T value) {
        return new Success<>(value);
    }

    static <T, E> Result<T, E> failure(E error) {
        return new Failure<>(error);
    }
}
