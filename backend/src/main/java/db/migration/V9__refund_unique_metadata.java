package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;

/** Remove only a UNIQUE constraint on exactly payment_id, preserving every other index/FK. */
public class V9__refund_unique_metadata extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 202610241; }
    @Override public void migrate(Context context) throws Exception {
        Connection c=context.getConnection();
        boolean mysql=c.getMetaData().getDatabaseProductName().equals("MySQL");
        String schema;
        try(Statement st=c.createStatement();ResultSet r=st.executeQuery(mysql?"select database()":"select current_schema")) {r.next();schema=r.getString(1);}
        String query=mysql
            ? "select index_name,column_name from information_schema.statistics where table_schema=? and table_name='payment_refunds' and non_unique=0 order by index_name,seq_in_index"
            : "select tc.constraint_name,kcu.column_name from information_schema.table_constraints tc join information_schema.key_column_usage kcu on tc.constraint_catalog=kcu.constraint_catalog and tc.constraint_schema=kcu.constraint_schema and tc.constraint_name=kcu.constraint_name where lower(tc.table_schema)=lower(?) and lower(tc.table_name)='payment_refunds' and upper(tc.constraint_type)='UNIQUE' order by tc.constraint_name,kcu.ordinal_position";
        Map<String,List<String>> indexes=new LinkedHashMap<>();
        try(PreparedStatement ps=c.prepareStatement(query)) {
            ps.setString(1,schema);try(ResultSet r=ps.executeQuery()) {while(r.next()) indexes.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(r.getString(2));}
        }
        // H2 may keep the old UNIQUE index alive for the child FK even after dropping its constraint.
        // Release that FK first, then restore it on a non-unique lookup index.
        List<String> childKeys=new ArrayList<>();
        if(!mysql)try(PreparedStatement ps=c.prepareStatement("select tc.constraint_name from information_schema.table_constraints tc join information_schema.key_column_usage k on tc.constraint_name=k.constraint_name and tc.constraint_schema=k.constraint_schema where tc.table_schema=? and tc.table_name='payment_refunds' and tc.constraint_type='FOREIGN KEY' and k.column_name='payment_id'")) {
            ps.setString(1,schema);try(ResultSet r=ps.executeQuery()){while(r.next())childKeys.add(r.getString(1));}
        }
        for(String key:childKeys)try(Statement st=c.createStatement()){st.execute("ALTER TABLE payment_refunds DROP CONSTRAINT \""+key+"\"");}
        if(mysql)try(Statement st=c.createStatement()) {st.execute("CREATE INDEX refund_payment_lookup ON payment_refunds(payment_id)");}
        for(var index:indexes.entrySet()) if(index.getValue().size()==1 && "payment_id".equalsIgnoreCase(index.getValue().getFirst())) {
            String name=index.getKey().replace("`","``").replace("\"","\"\"");
            try(Statement st=c.createStatement()) {st.execute(mysql?"ALTER TABLE payment_refunds DROP INDEX `"+name+"`":"ALTER TABLE payment_refunds DROP CONSTRAINT \""+name+"\"");}
        }
        for(String key:childKeys)try(Statement st=c.createStatement()){st.execute("ALTER TABLE payment_refunds ADD CONSTRAINT \""+key+"\" FOREIGN KEY(payment_id) REFERENCES payment_transactions(id)");}
    }
}
