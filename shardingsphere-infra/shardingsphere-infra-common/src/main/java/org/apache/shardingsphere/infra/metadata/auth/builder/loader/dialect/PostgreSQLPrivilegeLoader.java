/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.infra.metadata.auth.builder.loader.dialect;

import org.apache.shardingsphere.infra.metadata.auth.builder.loader.PrivilegeLoader;
import org.apache.shardingsphere.infra.metadata.auth.model.privilege.PrivilegeType;
import org.apache.shardingsphere.infra.metadata.auth.model.privilege.ShardingSpherePrivilege;
import org.apache.shardingsphere.infra.metadata.auth.model.privilege.database.SchemaPrivilege;
import org.apache.shardingsphere.infra.metadata.auth.model.privilege.database.TablePrivilege;
import org.apache.shardingsphere.infra.metadata.auth.model.user.Grantee;
import org.apache.shardingsphere.infra.metadata.auth.model.user.ShardingSphereUser;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * PostgreSQL privilege loader.
 */
public final class PostgreSQLPrivilegeLoader implements PrivilegeLoader {

    private static final String ROLES_SQL = "select * from pg_roles WHERE rolname IN (%s)";

    private static final String TABLE_PRIVILEGE_SQL = "SELECT grantor, grantee, table_catalog, table_name, privilege_type, is_grantable from information_schema.table_privileges WHERE grantee IN (%s)";

    @Override
    public Map<ShardingSphereUser, ShardingSpherePrivilege> load(final Collection<ShardingSphereUser> users, final DataSource dataSource) throws SQLException {
        Map<ShardingSphereUser, ShardingSpherePrivilege> result = new LinkedHashMap<>();
        users.forEach(user -> result.put(user, new ShardingSpherePrivilege()));
        fillTablePrivilege(result, dataSource, users);
        fillRolePrivilege(result, dataSource, users);
        return result;
    }
    
    private void fillTablePrivilege(final Map<ShardingSphereUser, ShardingSpherePrivilege> privileges, final DataSource dataSource, final Collection<ShardingSphereUser> users) throws SQLException {
        Map<ShardingSphereUser, Map<String, Map<String, List<PrivilegeType>>>> privilegeCache = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            try (ResultSet resultSet = statement.executeQuery(getTablePrivilegeSQL(users))) {
                while (resultSet.next()) {
                    collectPrivilege(privilegeCache, resultSet);
                }
            }
        }
        fillTablePrivilege(privilegeCache, privileges);
    }

    private void fillTablePrivilege(final Map<ShardingSphereUser, Map<String, Map<String, List<PrivilegeType>>>> privilegeCache, final Map<ShardingSphereUser, ShardingSpherePrivilege> privileges) {
        for (ShardingSphereUser user : privilegeCache.keySet()) {
            for (String db : privilegeCache.get(user).keySet()) {
                for (String tableName : privilegeCache.get(user).get(db).keySet()) {
                    TablePrivilege tablePrivilege = new TablePrivilege(tableName, privilegeCache.get(user).get(db).get(tableName));
                    ShardingSpherePrivilege privilege = privileges.get(user);
                    if (!privilege.getDatabasePrivilege().getSpecificPrivileges().containsKey(db)) {
                        privilege.getDatabasePrivilege().getSpecificPrivileges().put(db, new SchemaPrivilege(db));
                    }
                    privilege.getDatabasePrivilege().getSpecificPrivileges().get(db).getSpecificPrivileges().put(tableName, tablePrivilege);
                }
            }
        }
    }

    private void collectPrivilege(final Map<ShardingSphereUser, Map<String, Map<String, List<PrivilegeType>>>> privilegeCache, final ResultSet resultSet) throws SQLException {
        String db = resultSet.getString("table_catalog");
        String tableName = resultSet.getString("table_name");
        String privilegeType = resultSet.getString("privilege_type");
        Boolean hasPrivilege = resultSet.getString("is_grantable").equalsIgnoreCase("TRUE");
        String grantee = resultSet.getString("grantee");
        if (hasPrivilege) {
            privilegeCache
                    .computeIfAbsent(new ShardingSphereUser(grantee, "", ""), k -> new HashMap<>())
                    .computeIfAbsent(db, k -> new HashMap<>())
                    .computeIfAbsent(tableName, k -> new ArrayList<>())
                    .add(getPrivilegeType(privilegeType));
        }
    }

    private void fillRolePrivilege(final Map<ShardingSphereUser, ShardingSpherePrivilege> privileges, final DataSource dataSource, final Collection<ShardingSphereUser> users) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            try (ResultSet resultSet = statement.executeQuery(getRolePrivilegeSQL(users))) {
                while (resultSet.next()) {
                    fillRolePrivilege(privileges, resultSet);
                }
            }
        }
    }

    private void fillRolePrivilege(final Map<ShardingSphereUser, ShardingSpherePrivilege> privileges, final ResultSet resultSet) throws SQLException {
        Optional<ShardingSphereUser> user = getShardingSphereUser(privileges, resultSet);
        if (user.isPresent()) {
            privileges.get(user.get()).getAdministrativePrivilege().getPrivileges().addAll(loadRolePrivileges(resultSet));
        }
    }

    private Optional<ShardingSphereUser> getShardingSphereUser(final Map<ShardingSphereUser, ShardingSpherePrivilege> privileges, final ResultSet resultSet) throws SQLException {
        Grantee grantee = new Grantee(resultSet.getString("rolname"), "");
        return privileges.keySet().stream().filter(each -> each.getGrantee().equals(grantee)).findFirst();
    }

    private Collection<PrivilegeType> loadRolePrivileges(final ResultSet resultSet) throws SQLException {
        Collection<PrivilegeType> result = new LinkedList<>();
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolsuper"), PrivilegeType.SUPER, result);
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolcreaterole"), PrivilegeType.CREATE_ROLE, result);
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolcreatedb"), PrivilegeType.CREATE_DATABASE, result);
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolreplication"), PrivilegeType.REPL_CLIENT, result);
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolinherit"), PrivilegeType.INHERIT, result);
        addToPrivilegeTypesIfPresent(resultSet.getBoolean("rolcanlogin"), PrivilegeType.CAN_LOGIN, result);
        return result;
    }

    private String getTablePrivilegeSQL(final Collection<ShardingSphereUser> users) {
        String userList = users.stream().map(each -> String.format("'%s'", each.getGrantee().getUsername()))
                .collect(Collectors.joining(", "));
        return String.format(TABLE_PRIVILEGE_SQL, userList);
    }

    private String getRolePrivilegeSQL(final Collection<ShardingSphereUser> users) {
        String userList = users.stream().map(each -> String.format("'%s'", each.getGrantee().getUsername()))
                .collect(Collectors.joining(", "));
        return String.format(ROLES_SQL, userList);
    }

    private PrivilegeType getPrivilegeType(final String privilege) {
        switch (privilege) {
            case "SELECT":
                return PrivilegeType.SELECT;
            case "INSERT":
                return PrivilegeType.INSERT;
            case "UPDATE":
                return PrivilegeType.UPDATE;
            case "DELETE":
                return PrivilegeType.DELETE;
            case "TRUNCATE":
                return PrivilegeType.TRUNCATE;
            case "REFERENCES":
                return PrivilegeType.REFERENCES;
            case "TRIGGER":
                return PrivilegeType.TRIGGER;
            case "CREATE":
                return PrivilegeType.CREATE;
            case "EXECUTE":
                return PrivilegeType.EXECUTE;
            case "USAGE":
                return PrivilegeType.USAGE;
            case "CONNECT":
                return PrivilegeType.CONNECT;
            case "TEMPORARY":
                return PrivilegeType.TEMPORARY;
            default:
                throw new UnsupportedOperationException(privilege);
        }
    }

    private void addToPrivilegeTypesIfPresent(final boolean hasPrivilege, final PrivilegeType privilegeType, final Collection<PrivilegeType> target) {
        if (hasPrivilege) {
            target.add(privilegeType);
        }
    }

    @Override
    public String getDatabaseType() {
        return "PostgreSQL";
    }
}