/*
Copyright 2009-2019 Igor Polevoy

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package org.javalite.activejdbc;

import org.javalite.activejdbc.associations.Many2ManyAssociation;
import org.javalite.activejdbc.logging.LogFilter;
import org.javalite.activejdbc.logging.LogLevel;
import org.javalite.common.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;

import static java.util.Collections.emptyList;
import static org.javalite.common.Collections.map;

/**
 * @author Igor Polevoy
 */
class MetaModels {

    private static final String DB_NAME = "dbName";
    private static final String DB_TYPE = "dbType";
    private static final String MODEL_CLASS = "modelClass";
    private static final String COLUMN_METADATA = "columnMetadata";
    private static final String COLUMN_METADATA_NAME = "columnName";
    private static final String COLUMN_METADATA_TYPE = "columnType";
    private static final String COLUMN_METADATA_SIZE = "columnSize";
    private static final String ASSOCIATIONS = "associations";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetaModels.class);

    private final Map<String, MetaModel> metaModelsByTableName = new CaseInsensitiveMap<>();
    private final Map<String, MetaModel> metaModelsByClassName = new HashMap<>();
    //these are all many to many associations across all models.
    private final List<Many2ManyAssociation> many2ManyAssociations = new ArrayList<>();
    private final Map<Class, ModelRegistry> modelRegistries = new HashMap<>();

    void addMetaModel(MetaModel mm, Class<? extends Model> modelClass) {
        Object o = metaModelsByClassName.put(modelClass.getName(), mm);
        if (o != null) {
            LogFilter.log(LOGGER, LogLevel.WARNING, "Double-register: {}: {}", modelClass, o);
        }
        o = metaModelsByTableName.put(mm.getTableName(), mm);
        many2ManyAssociations.addAll(mm.getManyToManyAssociations(Collections.<Association>emptyList()));
        if (o != null) {
            LogFilter.log(LOGGER, LogLevel.WARNING, "Double-register: {}: {}", mm.getTableName(), o);
        }
    }

    ModelRegistry getModelRegistry(Class<? extends Model> modelClass) {
        ModelRegistry modelRegistry = modelRegistries.get(modelClass);
        if (modelRegistry == null) {
            modelRegistry = new ModelRegistry();
            modelRegistries.put(modelClass, modelRegistry);
        }
        return modelRegistry;
    }

    MetaModel getMetaModel(Class<? extends Model> modelClass) {
        return metaModelsByClassName.get(modelClass.getName());
    }

    MetaModel getMetaModel(String tableName) {
        return metaModelsByTableName.get(tableName);
    }

    String[] getTableNames(String dbName) {

        ArrayList<String> tableNames = new ArrayList<>();
        for (MetaModel metaModel : metaModelsByTableName.values()) {
            if (metaModel.getDbName().equals(dbName)) {
                tableNames.add(metaModel.getTableName());
            }
        }
        return tableNames.toArray(new String[tableNames.size()]);
    }

    Class<? extends Model> getModelClass(String tableName) {
        MetaModel mm = metaModelsByTableName.get(tableName);
        return mm == null ? null : mm.getModelClass();
    }

    String getTableName(Class<? extends Model> modelClass) {
        return metaModelsByClassName.containsKey(modelClass.getName())
                ? metaModelsByClassName.get(modelClass.getName()).getTableName() : null;
    }

    public void setColumnMetadata(String table, Map<String, ColumnMetadata> metaParams) {
        metaModelsByTableName.get(table).setColumnMetadata(metaParams);
    }

    /**
     * An edge is a table in a many to many relationship that is not a join.
     *
     * @param join join table
     *
     * @return edges for a join.
     */
    protected List<String> getEdges(String join) {
        List<String> results = new ArrayList<>();
        for (Many2ManyAssociation a : many2ManyAssociations) {
            if (a.getJoin().equalsIgnoreCase(join)) {
                results.add(getMetaModel(a.getSourceClass()).getTableName());
                results.add(getMetaModel(a.getTargetClass()).getTableName());
            }
        }
        return results;
    }

    protected String toJSON() {
        List models = new ArrayList();
        for (MetaModel metaModel : metaModelsByTableName.values()) {
            List<Map<String, Object>> associations = new ArrayList<Map<String, Object>>();
            for (Association association : metaModel.getAssociations()) {
                associations.add(association.toMap());
            }
            Map<String, Object> model = new HashMap<String, Object>();
            model.put(MODEL_CLASS, metaModel.getModelClass().getName());
            model.put(DB_TYPE, metaModel.getDbType());
            model.put(DB_NAME, metaModel.getDbName());
            model.put(COLUMN_METADATA, metaModel.getColumnMetadata());
            model.put(ASSOCIATIONS, associations);
            models.add(model);
        }
        return JsonHelper.toJsonString(models, false);
    }

    protected void fromJSON(String json) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType methodType = MethodType.methodType(void.class, Map.class);

            for (Object o : JsonHelper.toList(json)) {
                Map metaModelMap = (Map) o;

                MetaModel metaModel = new MetaModel(
                        (String) metaModelMap.get(DB_NAME),
                        (Class<? extends Model>) Class.forName((String) metaModelMap.get(MODEL_CLASS)),
                        (String) metaModelMap.get(DB_TYPE)
                );

                Map<String, ColumnMetadata> columnMetadataMap = new HashMap<>();
                Map columnMetadata = (Map) metaModelMap.get(COLUMN_METADATA);
                if (columnMetadata == null) {
                    columnMetadata = new HashMap();
                }
                for (Object column : columnMetadata.keySet()) {
                    Map map = (Map) columnMetadata.get(column);
                    Map metadata = (Map) map;
                    String columnName = (String) column;
                    String columnType = (String) metadata.get(COLUMN_METADATA_TYPE);
                    Integer columnSize = (Integer) metadata.get(COLUMN_METADATA_SIZE);
                    columnMetadataMap.put(
                            columnName,
                            new ColumnMetadata(
                                    (String) metadata.get(COLUMN_METADATA_NAME),
                                    columnType,
                                    columnSize
                            )
                    );
                }

                metaModel.setColumnMetadata(columnMetadataMap);
                List associations = metaModelMap.containsKey(ASSOCIATIONS) ? (List) metaModelMap.get(ASSOCIATIONS) : new ArrayList();
                for (Object a : associations) {
                    Map map = ((Map) a);
                    metaModel.addAssociation(
                            (Association) lookup.findConstructor(
                                    Class.forName((String) map.get(Association.CLASS)),
                                    methodType
                            ).invoke(map)
                    );
                }

                addMetaModel(metaModel, metaModel.getModelClass());
            }
        } catch (Throwable e) {
            throw new InitException("Cannot load metadata", e);
        }
    }
}
