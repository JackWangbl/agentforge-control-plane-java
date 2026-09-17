package com.agentforge.controlplane.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 前端 apiError() 只认 FastAPI 的 {"detail": ...} 结构：字符串直接展示，
 * 数组则取每项的 msg 拼起来。这里保持一致，前端不用改。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("detail", ex.getDetail()));
    }

    /** 校验失败对齐 Pydantic：422 + detail 数组，每项带 loc/msg/type。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            items.add(Map.of(
                    "loc", List.of("body", error.getField()),
                    "msg", error.getDefaultMessage() == null ? "字段校验失败" : error.getDefaultMessage(),
                    "type", "value_error"));
        }
        ex.getBindingResult().getGlobalErrors().forEach(error -> items.add(Map.of(
                "loc", List.of("body"),
                "msg", error.getDefaultMessage() == null ? "请求校验失败" : error.getDefaultMessage(),
                "type", "value_error")));
        if (items.isEmpty()) {
            items.add(Map.of("loc", List.of("body"), "msg", "请求校验失败", "type", "value_error"));
        }
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", items));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", List.of(
                Map.of("loc", List.of("body"), "msg", "请求体不是合法 JSON", "type", "value_error"))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "请求参数不合法" : ex.getMessage();
        return ResponseEntity.badRequest().body(Map.of("detail", message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleMissing(NoResourceFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "服务内部错误：" + message));
    }
}
