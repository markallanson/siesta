/*
 * Copyright (c) 2017 Cadenza United Kingdom Limited.
 *
 * All rights reserved.  May not be used without permission.
 */

package com.cadenzauk.siesta.catalog;

import com.cadenzauk.core.function.Function1;
import com.cadenzauk.core.function.FunctionOptional1;
import com.cadenzauk.core.reflect.ClassUtil;
import com.cadenzauk.core.reflect.Factory;
import com.cadenzauk.core.reflect.FieldUtil;
import com.cadenzauk.core.reflect.MethodInfo;
import com.cadenzauk.core.util.OptionalUtil;
import com.cadenzauk.siesta.Alias;
import com.cadenzauk.siesta.DataType;
import com.cadenzauk.siesta.Database;
import com.cadenzauk.siesta.RowMapper;
import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

public class Table<R> {
    private final Database database;
    private final Class<R> rowClass;
    private final String schema;
    private final String tableName;
    private final Impl<?> impl;

    private <B> Table(Builder<R,B> builder) {
        database = builder.database;
        rowClass = builder.rowClass;
        schema = builder.schema;
        tableName = builder.tableName;
        impl = new Impl<>(builder.newBuilder, builder.buildRow, builder.columns);
    }

    public Class<R> rowClass() {
        return rowClass;
    }

    public Database database() {
        return database;
    }

    public String schema() {
        return schema;
    }

    public String tableName() {
        return tableName;
    }

    public Stream<Column<?,R>> columns() {
        return impl.columns.stream().map(Function.identity());
    }

    public RowMapper<R> rowMapper(String s) {
        return impl.rowMapper(Optional.of(s));
    }

    public String qualifiedName() {
        return schema + "." + tableName();
    }

    public Alias<R> as(String alias) {
        return new Alias<>(this, alias);
    }

    public void insert(JdbcTemplate jdbcTemplate, R row) {
        impl.insert(jdbcTemplate, row);
    }

    public <T> Column<T,R> column(MethodInfo<R,T> methodInfo) {
        String columnName = database.columnNameFor(methodInfo);
        return DataType.of(methodInfo.effectiveType())
            .flatMap(dataType -> findColumn(dataType, columnName))
            .orElseThrow(() -> new IllegalArgumentException("No such column as " + columnName + " in " + qualifiedName()));
    }

    private <T> Optional<Column<T,R>> findColumn(DataType<T> dataType, String columnName) {
        return columnsOfType(dataType)
            .filter(c -> StringUtils.equals(c.name(), columnName))
            .findFirst();
    }

    private <T> Stream<Column<T,R>> columnsOfType(DataType<T> dataType) {
        return columns().flatMap(c -> c.as(dataType));
    }

    private class Impl<B> {
        private final Supplier<B> newBuilder;
        private final Function<B,R> buildRow;
        private final List<TableColumn<?,R,B>> columns;

        public Impl(Supplier<B> newBuilder, Function<B,R> buildRow, List<TableColumn<?,R,B>> columns) {
            this.newBuilder = newBuilder;
            this.buildRow = buildRow;
            this.columns = ImmutableList.copyOf(columns);
        }

        public void insert(JdbcTemplate jdbcTemplate, R row) {
            String sql = String.format("insert into %s.%s (%s) values (%s)",
                schema,
                tableName,
                columns.stream().map(Column::name).collect(joining(", ")),
                columns.stream().map(c -> "?").collect(joining(", ")));

            Object[] args = columns
                .stream()
                .map(c -> c.getter().apply(row).orElse(null))
                .toArray();

            jdbcTemplate.update(sql, args);
        }

        public RowMapper<R> rowMapper() {
            return rowMapper(Optional.empty());
        }

        public RowMapper<R> rowMapper(Optional<String> prefix) {
            return (rs, i) -> {
                B builder = newBuilder.get();
                columns.forEach(c -> c.extract(rs, builder, prefix));
                return buildRow.apply(builder);
            };
        }
    }

    public static final class Builder<R, B> {
        private final Database database;
        private final Class<R> rowClass;
        private final Class<B> builderClass;
        private final Function<B,R> buildRow;
        private final Set<String> excludedFields = new HashSet<>();
        private final List<TableColumn<?,R,B>> columns = new ArrayList<>();
        private String schema;
        private String tableName;
        private Supplier<B> newBuilder;

        public Builder(Database database, Class<R> rowClass, Class<B> builderClass, Function<B,R> buildRow) {
            this.database = database;
            this.rowClass = rowClass;
            this.builderClass = builderClass;
            this.buildRow = buildRow;

            Optional<javax.persistence.Table> tableAnnotation = ClassUtil.annotation(rowClass, javax.persistence.Table.class);
            this.schema = tableAnnotation
                .map(javax.persistence.Table::schema)
                .flatMap(OptionalUtil::ofBlankable)
                .orElse(database.defaultSchema());
            this.tableName = tableAnnotation
                .map(javax.persistence.Table::name)
                .flatMap(OptionalUtil::ofBlankable)
                .orElseGet(() -> database.namingStrategy().tableName(rowClass.getSimpleName()));
        }

        public Table<R> build() {
            if (newBuilder == null) {
                this.newBuilder = Factory.forClass(builderClass);
            }
            mappedClasses(rowClass)
                .flatMap(cls -> Arrays.stream(cls.getDeclaredFields()))
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !FieldUtil.hasAnnotation(Transient.class, f))
                .filter(f -> !excludedFields.contains(f.getName()))
                .forEach(f -> columns.add(TableColumn.fromField(database, rowClass, builderClass, f)));
            return new Table<>(this);
        }

        private Stream<Class<?>> mappedClasses(Class<?> startingWith) {
            return Stream.concat(
                ClassUtil.superclass(startingWith)
                    .filter(cls -> ClassUtil.hasAnnotation(cls, MappedSuperclass.class))
                    .map(this::mappedClasses)
                    .orElseGet(Stream::empty),
                Stream.of(startingWith));
        }

        public Builder<R,B> schema(String val) {
            schema = val;
            return this;
        }

        public Builder<R,B> tableName(String val) {
            tableName = val;
            return this;
        }

        public <BB> Builder<R,BB> builder(Class<BB> builderClass, Function<BB,R> buildRow) {
            return new Builder<>(database, rowClass, builderClass, buildRow)
                .schema(schema)
                .tableName(tableName);
        }

        public <T> Builder<R,B> column(Function1<R,T> getter, BiConsumer<B,T> setter) {
            return mandatory(getter, setter, Optional.empty());
        }

        public <T> Builder<R,B> column(FunctionOptional1<R,T> getter, BiConsumer<B,Optional<T>> setter) {
            return optional(getter, setter, Optional.empty());
        }

        public <T> Builder<R,B> column(Function1<R,T> getter, BiConsumer<B,T> setter, Consumer<TableColumn.Builder<T,R,B>> init) {
            return mandatory(getter, setter, Optional.of(init));
        }

        public <T> Builder<R,B> column(FunctionOptional1<R,T> getter, BiConsumer<B,Optional<T>> setter, Consumer<TableColumn.Builder<T,R,B>> init) {
            return optional(getter, setter, Optional.of(init));
        }

        public <T> Builder<R,B> mandatory(Function1<R,T> getter, BiConsumer<B,T> setter, Optional<Consumer<TableColumn.Builder<T,R,B>>> init) {
            MethodInfo<R,T> getterInfo = MethodInfo.of(getter);
            String name = getterInfo.method().getName();
            excludedFields.add(name);
            TableColumn.Builder<T,R,B> columnBuilder = TableColumn.mandatory(name, DataType.of(getterInfo.effectiveType()).get(), rowClass, getter, setter);
            init.ifPresent(x -> x.accept(columnBuilder));
            columns.add(columnBuilder.build());
            return this;
        }

        private <T> Builder<R,B> optional(FunctionOptional1<R,T> getter, BiConsumer<B,Optional<T>> setter, Optional<Consumer<TableColumn.Builder<T,R,B>>> init) {
            MethodInfo<R,T> getterInfo = MethodInfo.of(getter);
            String name = getterInfo.method().getName();
            excludedFields.add(name);
            TableColumn.Builder<T,R,B> columnBuilder = TableColumn.optional(name, DataType.of(getterInfo.effectiveType()).get(), rowClass, getter, setter);
            init.ifPresent(x -> x.accept(columnBuilder));
            columns.add(columnBuilder.build());
            return this;
        }
    }
}
