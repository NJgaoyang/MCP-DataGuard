package io.github.mcpdataguard.standalone;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded SELECT builder: user values are JDBC bound, never embedded in SQL.
 * Identifiers must belong to the intersected OWNER/AGENT allowed column set.
 * No arbitrary SQL, joins, functions, comments, subqueries or write path.
 */
final class ReadProjection {
 private ReadProjection(){}
 record Query(String sql,String predicateValue){}
 static String select(String table,Set<String> approvedColumns,int limit){
  return build(table,approvedColumns,limit,null,null,null,null).sql();
 }
 static Query build(String table,Set<String> allowed,int limit,
                    String filterColumn,String filterValue,
                    String sortColumn,String direction){
  String safeTable=Guard.identifier(table);
  if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Read limit must be 1..100");
  if(allowed==null||allowed.isEmpty())
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No authorized columns");
  Set<String> columns=allowed.stream().map(c->Guard.identifier(c).toLowerCase(Locale.ROOT))
    .collect(Collectors.toUnmodifiableSet());
  String projection=columns.stream().sorted().map(c->"`"+c+"`")
    .collect(Collectors.joining(","));
  StringBuilder sql=new StringBuilder("SELECT ").append(projection)
    .append(" FROM `").append(safeTable).append("`");
  if(filterColumn!=null&&!filterColumn.isBlank()){
   String col=Guard.identifier(filterColumn).toLowerCase(Locale.ROOT);
   if(!columns.contains(col))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Filter column not authorized");
   if(filterValue==null||filterValue.length()>256)
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Filter value required (<=256 chars)");
   sql.append(" WHERE `").append(col).append("` = ?");
  }else if(filterValue!=null)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Filter column required");
  if(sortColumn!=null&&!sortColumn.isBlank()){
   String col=Guard.identifier(sortColumn).toLowerCase(Locale.ROOT);
   if(!columns.contains(col))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Sort column not authorized");
   String sort=direction==null||direction.isBlank()?"ASC":direction.strip().toUpperCase(Locale.ROOT);
   if(!sort.equals("ASC")&&!sort.equals("DESC"))
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sort direction must be ASC or DESC");
   sql.append(" ORDER BY `").append(col).append("` ").append(sort);
  }else if(direction!=null&&!direction.isBlank())
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sort column required");
  sql.append(" LIMIT ").append(limit);
  return new Query(sql.toString(),filterColumn==null||filterColumn.isBlank()?null:filterValue);
 }
}
