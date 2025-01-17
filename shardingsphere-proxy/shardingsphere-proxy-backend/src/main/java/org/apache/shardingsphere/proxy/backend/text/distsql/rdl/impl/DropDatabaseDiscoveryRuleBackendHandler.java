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

package org.apache.shardingsphere.proxy.backend.text.distsql.rdl.impl;

import org.apache.shardingsphere.dbdiscovery.api.config.DatabaseDiscoveryRuleConfiguration;
import org.apache.shardingsphere.dbdiscovery.api.config.rule.DatabaseDiscoveryDataSourceRuleConfiguration;
import org.apache.shardingsphere.dbdiscovery.distsql.parser.statement.DropDatabaseDiscoveryRuleStatement;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.exception.DatabaseDiscoveryRuleNotExistedException;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Drop database discovery rule backend handler.
 */
public final class DropDatabaseDiscoveryRuleBackendHandler extends RDLBackendHandler<DropDatabaseDiscoveryRuleStatement> {
    
    public DropDatabaseDiscoveryRuleBackendHandler(final DropDatabaseDiscoveryRuleStatement sqlStatement, final BackendConnection backendConnection) {
        super(sqlStatement, backendConnection);
    }
    
    @Override
    public void check(final String schemaName, final DropDatabaseDiscoveryRuleStatement sqlStatement) {
        Optional<DatabaseDiscoveryRuleConfiguration> ruleConfig = findCurrentRuleConfiguration(schemaName, DatabaseDiscoveryRuleConfiguration.class);
        if (!ruleConfig.isPresent()) {
            throw new DatabaseDiscoveryRuleNotExistedException(schemaName, sqlStatement.getRuleNames());
        }
        check(schemaName, ruleConfig.get(), sqlStatement);
    }
    
    private void check(final String schemaName, final DatabaseDiscoveryRuleConfiguration databaseDiscoveryRuleConfig, final DropDatabaseDiscoveryRuleStatement sqlStatement) {
        Collection<String> existRuleNames = databaseDiscoveryRuleConfig.getDataSources().stream().map(DatabaseDiscoveryDataSourceRuleConfiguration::getName).collect(Collectors.toList());
        Collection<String> notExistedRuleNames = sqlStatement.getRuleNames().stream().filter(each -> !existRuleNames.contains(each)).collect(Collectors.toList());
        if (!notExistedRuleNames.isEmpty()) {
            throw new DatabaseDiscoveryRuleNotExistedException(schemaName, notExistedRuleNames);
        }
    }
    
    @Override
    public void doExecute(final String schemaName, final DropDatabaseDiscoveryRuleStatement sqlStatement) {
        DatabaseDiscoveryRuleConfiguration ruleConfig = getCurrentRuleConfiguration(schemaName, DatabaseDiscoveryRuleConfiguration.class);
        sqlStatement.getRuleNames().forEach(each -> {
            DatabaseDiscoveryDataSourceRuleConfiguration databaseDiscoveryDataSourceRuleConfig = ruleConfig.getDataSources()
                    .stream().filter(dataSource -> dataSource.getName().equals(each)).findAny().get();
            ruleConfig.getDataSources().remove(databaseDiscoveryDataSourceRuleConfig);
            ruleConfig.getDiscoveryTypes().remove(databaseDiscoveryDataSourceRuleConfig.getDiscoveryTypeName());
        });
        if (ruleConfig.getDataSources().isEmpty()) {
            ProxyContext.getInstance().getMetaData(schemaName).getRuleMetaData().getConfigurations().remove(ruleConfig);
        }
    }
}
