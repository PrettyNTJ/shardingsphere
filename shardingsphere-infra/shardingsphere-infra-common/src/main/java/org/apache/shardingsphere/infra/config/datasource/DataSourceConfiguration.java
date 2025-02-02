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

package org.apache.shardingsphere.infra.config.datasource;

import com.google.common.base.CaseFormat;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.shardingsphere.infra.config.exception.ShardingSphereConfigurationException;
import org.apache.shardingsphere.spi.ShardingSphereServiceLoader;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;

/**
 * Data source configuration.
 */
@RequiredArgsConstructor
@Getter
public final class DataSourceConfiguration {
    
    public static final String CUSTOM_POOL_PROPS_KEY = "customPoolProps";
    
    private static final String GETTER_PREFIX = "get";
    
    private static final String SETTER_PREFIX = "set";
    
    private static final Collection<Class<?>> GENERAL_CLASS_TYPE;
    
    private static final Collection<String> SKIPPED_PROPERTY_NAMES;
    
    static {
        ShardingSphereServiceLoader.register(JDBCParameterDecorator.class);
        GENERAL_CLASS_TYPE = Sets.newHashSet(boolean.class, Boolean.class, int.class, Integer.class, long.class, Long.class, String.class, Collection.class, List.class);
        SKIPPED_PROPERTY_NAMES = Sets.newHashSet("loginTimeout");
    }
    
    private final String dataSourceClassName;
    
    private final Map<String, Object> props = new LinkedHashMap<>();
    
    private final Properties customPoolProps = new Properties();
    
    /**
     * Get data source configuration.
     *
     * @param dataSource data source
     * @return data source configuration
     */
    public static DataSourceConfiguration getDataSourceConfiguration(final DataSource dataSource) {
        DataSourceConfiguration result = new DataSourceConfiguration(dataSource.getClass().getName());
        result.props.putAll(findAllGetterProperties(dataSource));
        return result;
    }
    
    @SneakyThrows(ReflectiveOperationException.class)
    private static Map<String, Object> findAllGetterProperties(final Object target) {
        Collection<Method> allGetterMethods = findAllGetterMethods(target.getClass());
        Map<String, Object> result = new LinkedHashMap<>(allGetterMethods.size(), 1);
        for (Method each : allGetterMethods) {
            String propertyName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, each.getName().substring(GETTER_PREFIX.length()));
            if (GENERAL_CLASS_TYPE.contains(each.getReturnType()) && !SKIPPED_PROPERTY_NAMES.contains(propertyName)) {
                result.put(propertyName, each.invoke(target));
            }
        }
        return result;
    }
    
    private static Collection<Method> findAllGetterMethods(final Class<?> clazz) {
        Method[] methods = clazz.getMethods();
        Collection<Method> result = new HashSet<>(methods.length);
        for (Method each : methods) {
            if (each.getName().startsWith(GETTER_PREFIX) && 0 == each.getParameterTypes().length) {
                result.add(each);
            }
        }
        return result;
    }
    
    /**
     * Create data source.
     *
     * @return data source
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @SneakyThrows(ReflectiveOperationException.class)
    public DataSource createDataSource() {
        DataSource result = (DataSource) Class.forName(dataSourceClassName).getConstructor().newInstance();
        Method[] methods = result.getClass().getMethods();
        Map<String, Object> allProps = new HashMap<>(props);
        allProps.putAll((Map) customPoolProps);
        for (Entry<String, Object> entry : allProps.entrySet()) {
            if (SKIPPED_PROPERTY_NAMES.contains(entry.getKey())) {
                continue;
            }
            try {
                Optional<Method> setterMethod = findSetterMethod(methods, entry.getKey());
                if (setterMethod.isPresent() && null != entry.getValue()) {
                    setDataSourceField(setterMethod.get(), result, entry.getValue());
                }
            } catch (final IllegalArgumentException ex) {
                throw new ShardingSphereConfigurationException("Incorrect configuration item: the property %s of the dataSource, because %s", entry.getKey(), ex.getMessage());
            }
        }
        return JDBCParameterDecoratorHelper.decorate(result);
    }
    
    private void setDataSourceField(final Method method, final DataSource target, final Object value) throws InvocationTargetException, IllegalAccessException {
        Class<?> paramType = method.getParameterTypes()[0];
        if (paramType == int.class) {
            method.invoke(target, Integer.parseInt(value.toString()));
        } else if (paramType == long.class) {
            method.invoke(target, Long.parseLong(value.toString()));
        } else if (paramType == boolean.class || paramType == Boolean.class) {
            method.invoke(target, Boolean.parseBoolean(value.toString()));
        } else if (paramType == String.class) {
            method.invoke(target, value.toString());
        } else {
            method.invoke(target, value);
        }
    }
    
    private Optional<Method> findSetterMethod(final Method[] methods, final String property) {
        String setterMethodName = SETTER_PREFIX + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, property);
        return Arrays.stream(methods)
                .filter(each -> each.getName().equals(setterMethodName) && 1 == each.getParameterTypes().length)
                .findFirst();
    }
    
    /**
     * Add property synonym to shared configuration.
     *
     * @param originalName original key for data source configuration property
     * @param synonym property synonym for configuration
     */
    public void addPropertySynonym(final String originalName, final String synonym) {
        if (props.containsKey(originalName)) {
            props.put(synonym, props.get(originalName));
        }
        // TODO fixes by #6709
        if (props.containsKey(synonym)) {
            props.put(originalName, props.get(synonym));
        }
    }
    
    @Override
    public boolean equals(final Object obj) {
        return this == obj || null != obj && getClass() == obj.getClass() && equalsByProperties((DataSourceConfiguration) obj);
    }
    
    private boolean equalsByProperties(final DataSourceConfiguration dataSourceConfig) {
        if (!dataSourceClassName.equals(dataSourceConfig.dataSourceClassName)) {
            return false;
        }
        for (Entry<String, Object> entry : props.entrySet()) {
            if (!dataSourceConfig.props.containsKey(entry.getKey())) {
                continue;
            }
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(dataSourceConfig.props.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public int hashCode() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Entry<String, Object> entry : props.entrySet()) {
            stringBuilder.append(entry.getKey()).append(entry.getValue());
        }
        return Objects.hashCode(dataSourceClassName, stringBuilder.toString());
    }
}
