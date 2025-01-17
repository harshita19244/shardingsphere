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

import com.google.common.collect.Sets;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.typed.TypedSPIRegistry;
import org.apache.shardingsphere.infra.yaml.swapper.YamlRuleConfigurationSwapperEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.exception.DuplicateTablesException;
import org.apache.shardingsphere.proxy.backend.exception.InvalidKeyGeneratorsException;
import org.apache.shardingsphere.proxy.backend.exception.InvalidShardingAlgorithmsException;
import org.apache.shardingsphere.proxy.backend.exception.ResourceNotExistedException;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingAutoTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.converter.ShardingRuleStatementConverter;
import org.apache.shardingsphere.sharding.distsql.parser.segment.TableRuleSegment;
import org.apache.shardingsphere.sharding.distsql.parser.statement.CreateShardingTableRuleStatement;
import org.apache.shardingsphere.sharding.spi.KeyGenerateAlgorithm;
import org.apache.shardingsphere.sharding.spi.ShardingAlgorithm;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Create sharding table rule backend handler.
 */
public final class CreateShardingTableRuleBackendHandler extends RDLBackendHandler<CreateShardingTableRuleStatement> {
    
    static {
        // TODO consider about register once only
        ShardingSphereServiceLoader.register(ShardingAlgorithm.class);
        ShardingSphereServiceLoader.register(KeyGenerateAlgorithm.class);
    }
    
    public CreateShardingTableRuleBackendHandler(final CreateShardingTableRuleStatement sqlStatement, final BackendConnection backendConnection) {
        super(sqlStatement, backendConnection);
    }
    
    @Override
    public void check(final String schemaName, final CreateShardingTableRuleStatement sqlStatement) {
        Collection<String> notExistResources = getNotExistedResources(schemaName, getResources(sqlStatement));
        if (!notExistResources.isEmpty()) {
            throw new ResourceNotExistedException(schemaName, notExistResources);
        }
        Collection<String> existLogicTables = getAllTables(schemaName);
        Set<String> duplicateTableNames = sqlStatement.getRules().stream().collect(Collectors.toMap(TableRuleSegment::getLogicTable, each -> 1, Integer::sum))
                .entrySet().stream().filter(entry -> entry.getValue() > 1).map(Entry::getKey).collect(Collectors.toSet());
        duplicateTableNames.addAll(sqlStatement.getRules().stream().map(TableRuleSegment::getLogicTable).filter(existLogicTables::contains).collect(Collectors.toSet()));
        if (!duplicateTableNames.isEmpty()) {
            throw new DuplicateTablesException(duplicateTableNames);
        }
        Collection<String> invalidTableAlgorithms = sqlStatement.getRules().stream().map(each -> each.getTableStrategy().getName()).distinct()
                .filter(each -> !TypedSPIRegistry.findRegisteredService(ShardingAlgorithm.class, each, new Properties()).isPresent())
                .collect(Collectors.toList());
        if (!invalidTableAlgorithms.isEmpty()) {
            throw new InvalidShardingAlgorithmsException(invalidTableAlgorithms);
        }
        Collection<String> invalidKeyGenerators = getKeyGenerators(sqlStatement).stream()
                .filter(each -> !TypedSPIRegistry.findRegisteredService(KeyGenerateAlgorithm.class, each, new Properties()).isPresent())
                .collect(Collectors.toList());
        if (!invalidKeyGenerators.isEmpty()) {
            throw new InvalidKeyGeneratorsException(invalidKeyGenerators);
        }
    }
    
    @Override
    public void doExecute(final String schemaName, final CreateShardingTableRuleStatement sqlStatement) {
        ShardingRuleConfiguration shardingRuleConfig = (ShardingRuleConfiguration) new YamlRuleConfigurationSwapperEngine()
                .swapToRuleConfigurations(Collections.singleton(ShardingRuleStatementConverter.convert(sqlStatement))).iterator().next();
        Optional<ShardingRuleConfiguration> existShardingRuleConfig = findCurrentRuleConfiguration(schemaName, ShardingRuleConfiguration.class);
        if (existShardingRuleConfig.isPresent()) {
            existShardingRuleConfig.get().getAutoTables().addAll(shardingRuleConfig.getAutoTables());
            existShardingRuleConfig.get().getShardingAlgorithms().putAll(shardingRuleConfig.getShardingAlgorithms());
            existShardingRuleConfig.get().getKeyGenerators().putAll(shardingRuleConfig.getKeyGenerators());
        } else {
            ProxyContext.getInstance().getMetaData(schemaName).getRuleMetaData().getConfigurations().add(shardingRuleConfig);
        }
    }
    
    private Collection<String> getAllTables(final String schemaName) {
        Collection<String> result = Sets.newHashSet(ProxyContext.getInstance().getMetaData(schemaName).getSchema().getAllTableNames());
        findCurrentRuleConfiguration(schemaName, ShardingRuleConfiguration.class).ifPresent(optional -> result.addAll(getShardingTables(optional)));
        return result;
    }
    
    private Collection<String> getShardingTables(final ShardingRuleConfiguration shardingRuleConfiguration) {
        Collection<String> result = new LinkedList<>();
        result.addAll(shardingRuleConfiguration.getTables().stream().map(ShardingTableRuleConfiguration::getLogicTable).collect(Collectors.toSet()));
        result.addAll(shardingRuleConfiguration.getAutoTables().stream().map(ShardingAutoTableRuleConfiguration::getLogicTable).collect(Collectors.toSet()));
        return result;
    }
    
    private Collection<String> getResources(final CreateShardingTableRuleStatement sqlStatement) {
        Collection<String> result = new LinkedHashSet<>();
        sqlStatement.getRules().forEach(each -> result.addAll(each.getDataSources()));
        return result;
    }
    
    private Collection<String> getKeyGenerators(final CreateShardingTableRuleStatement sqlStatement) {
        return sqlStatement.getRules().stream().filter(each -> Objects.nonNull(each.getKeyGenerateStrategy()))
                .map(each -> each.getKeyGenerateStrategy().getName()).collect(Collectors.toSet());
    }
}
