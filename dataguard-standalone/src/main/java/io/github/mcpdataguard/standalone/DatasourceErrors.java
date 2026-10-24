package io.github.mcpdataguard.standalone;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=DatasourceApi.class)
public class DatasourceErrors {
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<Map<String,String>> status(ResponseStatusException ex){
  return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"数据源操作失败":ex.getReason()));
 }
 @ExceptionHandler(DuplicateKeyException.class)
 public ResponseEntity<Map<String,String>> duplicate(DuplicateKeyException ex){return ResponseEntity.status(409).body(Map.of("message","数据源编码已存在，请使用其他编码"));}
}
