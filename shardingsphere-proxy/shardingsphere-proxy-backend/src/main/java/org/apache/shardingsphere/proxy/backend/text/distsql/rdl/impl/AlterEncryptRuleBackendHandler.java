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

import org.apache.shardingsphere.encrypt.distsql.parser.segment.EncryptRuleSegment;
import org.apache.shardingsphere.encrypt.distsql.parser.statement.AlterEncryptRuleStatement;
import org.apache.shardingsphere.encrypt.api.config.EncryptRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.rule.EncryptTableRuleConfiguration;
import org.apache.shardingsphere.encrypt.spi.EncryptAlgorithm;
import org.apache.shardingsphere.encrypt.yaml.converter.EncryptRuleStatementConverter;
import org.apache.shardingsphere.infra.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.spi.typed.TypedSPIRegistry;
import org.apache.shardingsphere.infra.yaml.swapper.YamlRuleConfigurationSwapperEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.exception.EncryptRuleNotExistedException;
import org.apache.shardingsphere.proxy.backend.exception.InvalidEncryptorsException;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Alter encrypt rule backend handler.
 */
public final class AlterEncryptRuleBackendHandler extends RDLBackendHandler<AlterEncryptRuleStatement> {
    
    static {
        // TODO consider about register once only
        ShardingSphereServiceLoader.register(EncryptAlgorithm.class);
    }
    
    public AlterEncryptRuleBackendHandler(final AlterEncryptRuleStatement sqlStatement, final BackendConnection backendConnection) {
        super(sqlStatement, backendConnection);
    }
    
    @Override
    public void check(final String schemaName, final AlterEncryptRuleStatement sqlStatement) {
        Optional<EncryptRuleConfiguration> ruleConfig = findCurrentRuleConfiguration(schemaName, EncryptRuleConfiguration.class);
        if (!ruleConfig.isPresent()) {
            throw new EncryptRuleNotExistedException(schemaName, getAlteredRuleNames(sqlStatement));
        }
        check(schemaName, sqlStatement, ruleConfig.get());
    }
    
    private void check(final String schemaName, final AlterEncryptRuleStatement sqlStatement, final EncryptRuleConfiguration encryptRuleConfig) {
        checkAlteredTables(schemaName, encryptRuleConfig, sqlStatement);
        checkEncryptors(sqlStatement);
    }
    
    private void checkAlteredTables(final String schemaName, final EncryptRuleConfiguration encryptRuleConfig, final AlterEncryptRuleStatement sqlStatement) {
        Collection<String> existTables = getExistTables(encryptRuleConfig);
        Collection<String> notExistTables = getAlteredRuleNames(sqlStatement).stream().filter(each -> !existTables.contains(each)).collect(Collectors.toList());
        if (!notExistTables.isEmpty()) {
            throw new EncryptRuleNotExistedException(schemaName, notExistTables);
        }
    }
    
    private Collection<String> getExistTables(final EncryptRuleConfiguration encryptRuleConfig) {
        return encryptRuleConfig.getTables().stream().map(EncryptTableRuleConfiguration::getName).collect(Collectors.toList());
    }
    
    private void checkEncryptors(final AlterEncryptRuleStatement sqlStatement) {
        Collection<String> encryptors = new LinkedHashSet<>();
        sqlStatement.getRules().forEach(each -> encryptors.addAll(each.getColumns().stream()
                .map(column -> column.getEncryptor().getName()).collect(Collectors.toSet())));
        Collection<String> invalidEncryptors = encryptors.stream().filter(
            each -> !TypedSPIRegistry.findRegisteredService(EncryptAlgorithm.class, each, new Properties()).isPresent()).collect(Collectors.toList());
        if (!invalidEncryptors.isEmpty()) {
            throw new InvalidEncryptorsException(invalidEncryptors);
        }
    }
    
    @Override
    public void doExecute(final String schemaName, final AlterEncryptRuleStatement sqlStatement) {
        EncryptRuleConfiguration ruleConfig = getCurrentRuleConfiguration(schemaName, EncryptRuleConfiguration.class);
        EncryptRuleConfiguration alteredEncryptRuleConfiguration = new YamlRuleConfigurationSwapperEngine()
                .swapToRuleConfigurations(Collections.singleton(EncryptRuleStatementConverter.convert(sqlStatement.getRules()))).stream()
                .map(each -> (EncryptRuleConfiguration) each).findFirst().get();
        drop(sqlStatement, ruleConfig);
        ruleConfig.getTables().addAll(alteredEncryptRuleConfiguration.getTables());
        ruleConfig.getEncryptors().putAll(alteredEncryptRuleConfiguration.getEncryptors());
    }
    
    private void drop(final AlterEncryptRuleStatement sqlStatement, final EncryptRuleConfiguration encryptRuleConfig) {
        getAlteredRuleNames(sqlStatement).forEach(each -> {
            EncryptTableRuleConfiguration encryptTableRuleConfig = encryptRuleConfig.getTables().stream().filter(tableRule -> tableRule.getName().equals(each)).findAny().get();
            encryptRuleConfig.getTables().remove(encryptTableRuleConfig);
            encryptTableRuleConfig.getColumns().forEach(column -> encryptRuleConfig.getEncryptors().remove(column.getEncryptorName()));
        });
    }
    
    private Collection<String> getAlteredRuleNames(final AlterEncryptRuleStatement sqlStatement) {
        return sqlStatement.getRules().stream().map(EncryptRuleSegment::getTableName).collect(Collectors.toList());
    }
}
