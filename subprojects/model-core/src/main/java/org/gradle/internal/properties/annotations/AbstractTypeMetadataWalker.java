/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.properties.annotations;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import org.gradle.api.Named;
import org.gradle.api.provider.Provider;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.Collections.emptySet;

abstract class AbstractTypeMetadataWalker<T> implements TypeMetadataWalker<T> {
    private final TypeMetadataStore typeMetadataStore;
    private final Class<? extends Annotation> nestedAnnotation;

    private AbstractTypeMetadataWalker(TypeMetadataStore typeMetadataStore, Class<? extends Annotation> nestedAnnotation) {
        this.typeMetadataStore = typeMetadataStore;
        this.nestedAnnotation = nestedAnnotation;
    }

    @Override
    public void walk(T root, NodeMetadataVisitor<T> visitor) {
        walk(root, null, visitor, emptySet());
    }

    private void walk(T node, @Nullable String qualifiedName, NodeMetadataVisitor<T> visitor, Set<T> previousNestedNodesWalkedOnPath) {
        Class<?> nodeType = resolveType(node);
        TypeMetadata typeMetadata = typeMetadataStore.getTypeMetadata(nodeType);
        if (Provider.class.isAssignableFrom(nodeType)) {
            handleProvider(node, child -> walk(child, qualifiedName, visitor, previousNestedNodesWalkedOnPath));
        } else if (Map.class.isAssignableFrom(nodeType) && !typeMetadata.hasAnnotatedProperties()) {
            handleMap(node, (name, child) -> walk(child, getQualifiedName(qualifiedName, name), visitor, previousNestedNodesWalkedOnPath));
        } else if (Iterable.class.isAssignableFrom(nodeType) && !typeMetadata.hasAnnotatedProperties()) {
            handleIterable(node, (name, child) -> walk(child, getQualifiedName(qualifiedName, name), visitor, previousNestedNodesWalkedOnPath));
        } else {
            handleNested(node, typeMetadata, qualifiedName, visitor, previousNestedNodesWalkedOnPath);
        }
    }

    private void handleNested(T node, TypeMetadata typeMetadata, @Nullable String qualifiedName, NodeMetadataVisitor<T> visitor, Set<T> previousNestedNodesOnPath) {
        if (previousNestedNodesOnPath.contains(node)) {
            return;
        }

        visitor.visitNested(typeMetadata, qualifiedName, node);
        Set<T> nestedNodesOnPath = newIdentitySetOf(previousNestedNodesOnPath, node);
        typeMetadata.getPropertiesMetadata().forEach(propertyMetadata -> {
            String childQualifiedName = getQualifiedName(qualifiedName, propertyMetadata.getPropertyName());
            if (propertyMetadata.getPropertyType() == nestedAnnotation) {
                Optional<T> childOptional = getChild(node, propertyMetadata);
                childOptional.ifPresent(child -> walk(child, childQualifiedName, visitor, nestedNodesOnPath));
            } else {
                visitor.visitLeaf(childQualifiedName, propertyMetadata, () -> getChild(node, propertyMetadata).orElse(null));
            }
        });
    }

    abstract void handleProvider(T node, Consumer<T> handler);

    abstract protected void handleMap(T node, BiConsumer<String, T> handler);

    abstract protected void handleIterable(T node, BiConsumer<String, T> handler);

    abstract protected Class<?> resolveType(T type);

    abstract protected Optional<T> getChild(T parent, PropertyMetadata property);

    abstract Set<T> newIdentitySetOf(Set<T> initialSet, T newElement);

    private static String getQualifiedName(@Nullable String parentPropertyName, String childPropertyName) {
        return parentPropertyName == null
            ? childPropertyName
            : parentPropertyName + "." + childPropertyName;
    }

    static class InstanceTypeMetadataWalker extends AbstractTypeMetadataWalker<Object> {
        public InstanceTypeMetadataWalker(TypeMetadataStore typeMetadataStore, Class<? extends Annotation> nestedAnnotation) {
            super(typeMetadataStore, nestedAnnotation);
        }

        @Override
        protected Class<?> resolveType(Object value) {
            return value.getClass();
        }

        @Override
        protected void handleProvider(Object node, Consumer<Object> handler) {
            handler.accept(((Provider<?>) node).get());
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void handleMap(Object node, BiConsumer<String, Object> handler) {
            ((Map<String, Object>) node).forEach(handler);
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void handleIterable(Object node, BiConsumer<String, Object> handler) {
            int counter = 1;
            for (Object o : (Iterable<Object>) node) {
                handler.accept("$" + counter++, o);
            }
        }

        @Override
        protected Optional<Object> getChild(Object parent, PropertyMetadata property) {
            try {
                return Optional.ofNullable(property.getGetterMethod().invoke(parent));
            } catch (Exception e) {
                // TODO Handle this
                throw new RuntimeException(e);
            }
        }

        @Override
        protected Set<Object> newIdentitySetOf(Set<Object> initialSet, Object newElement) {
            Set<Object> set = Sets.newIdentityHashSet();
            set.addAll(initialSet);
            set.add(newElement);
            return Collections.unmodifiableSet(set);
        }
    }

    static class StaticTypeMetadataWalker extends AbstractTypeMetadataWalker<TypeToken<?>> {
        public StaticTypeMetadataWalker(TypeMetadataStore typeMetadataStore, Class<? extends Annotation> nestedAnnotation) {
            super(typeMetadataStore, nestedAnnotation);
        }

        @Override
        protected Class<?> resolveType(TypeToken<?> type) {
            return type.getRawType();
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void handleProvider(TypeToken<?> node, Consumer<TypeToken<?>> handler) {
            handler.accept(extractNestedType((TypeToken<Provider<?>>) node, Provider.class, 0));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void handleMap(TypeToken<?> node, BiConsumer<String, TypeToken<?>> handler) {
            handler.accept(
                "<key>",
                extractNestedType((TypeToken<Map<?, ?>>) node, Map.class, 1));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void handleIterable(TypeToken<?> node, BiConsumer<String, TypeToken<?>> handler) {
            TypeToken<?> nestedType = extractNestedType((TypeToken<? extends Iterable<?>>) node, Iterable.class, 0);
            handler.accept(determinePropertyName(nestedType), nestedType);
        }

        @Override
        protected Optional<TypeToken<?>> getChild(TypeToken<?> parent, PropertyMetadata property) {
            return Optional.of(TypeToken.of(property.getGetterMethod().getGenericReturnType()));
        }

        private static String determinePropertyName(TypeToken<?> nestedType) {
            return Named.class.isAssignableFrom(nestedType.getRawType())
                ? "<name>"
                : "*";
        }

        private static <T> TypeToken<?> extractNestedType(TypeToken<T> beanType, Class<? super T> parameterizedSuperClass, int typeParameterIndex) {
            ParameterizedType type = (ParameterizedType) beanType.getSupertype(parameterizedSuperClass).getType();
            return TypeToken.of(type.getActualTypeArguments()[typeParameterIndex]);
        }

        @Override
        Set<TypeToken<?>> newIdentitySetOf(Set<TypeToken<?>> initialSet, TypeToken<?> newElement) {
            return ImmutableSet.<TypeToken<?>>builder()
                .addAll(initialSet)
                .add(newElement)
                .build();
        }
    }
}
