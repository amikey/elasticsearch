/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless;

import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.index.fielddata.ScriptDocValues;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The entire API for Painless.  Also used as a whitelist for checking for legal
 * methods and fields during at both compile-time and runtime.
 */
public final class Definition {

    /**
     * The default language API to be used with Painless.  The second construction is used
     * to finalize all the variables, so there is no mistake of modification afterwards.
     */
    static Definition INSTANCE = new Definition(new Definition());

    public enum Sort {
        VOID(       void.class      , 0 , true  , false , false , false ),
        BOOL(       boolean.class   , 1 , true  , true  , false , true  ),
        BYTE(       byte.class      , 1 , true  , false , true  , true  ),
        SHORT(      short.class     , 1 , true  , false , true  , true  ),
        CHAR(       char.class      , 1 , true  , false , true  , true  ),
        INT(        int.class       , 1 , true  , false , true  , true  ),
        LONG(       long.class      , 2 , true  , false , true  , true  ),
        FLOAT(      float.class     , 1 , true  , false , true  , true  ),
        DOUBLE(     double.class    , 2 , true  , false , true  , true  ),

        VOID_OBJ(   Void.class      , 1 , true  , false , false , false ),
        BOOL_OBJ(   Boolean.class   , 1 , false , true  , false , false ),
        BYTE_OBJ(   Byte.class      , 1 , false , false , true  , false ),
        SHORT_OBJ(  Short.class     , 1 , false , false , true  , false ),
        CHAR_OBJ(   Character.class , 1 , false , false , true  , false ),
        INT_OBJ(    Integer.class   , 1 , false , false , true  , false ),
        LONG_OBJ(   Long.class      , 1 , false , false , true  , false ),
        FLOAT_OBJ(  Float.class     , 1 , false , false , true  , false ),
        DOUBLE_OBJ( Double.class    , 1 , false , false , true  , false ),

        NUMBER(     Number.class    , 1 , false , false , false , false ),
        STRING(     String.class    , 1 , false , false , false , true  ),

        OBJECT(     null            , 1 , false , false , false , false ),
        DEF(        null            , 1 , false , false , false , false ),
        ARRAY(      null            , 1 , false , false , false , false );

        public final Class<?> clazz;
        public final int size;
        public final boolean primitive;
        public final boolean bool;
        public final boolean numeric;
        public final boolean constant;

        Sort(final Class<?> clazz, final int size, final boolean primitive,
             final boolean bool, final boolean numeric, final boolean constant) {
            this.clazz = clazz;
            this.size = size;
            this.bool = bool;
            this.primitive = primitive;
            this.numeric = numeric;
            this.constant = constant;
        }
    }

    public static final class Type {
        public final String name;
        public final int dimensions;
        public final Struct struct;
        public final Class<?> clazz;
        public final org.objectweb.asm.Type type;
        public final Sort sort;

        private Type(final String name, final int dimensions, final Struct struct,
                     final Class<?> clazz, final org.objectweb.asm.Type type, final Sort sort) {
            this.name = name;
            this.dimensions = dimensions;
            this.struct = struct;
            this.clazz = clazz;
            this.type = type;
            this.sort = sort;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            final Type type = (Type)object;

            return this.type.equals(type.type) && struct.equals(type.struct);
        }

        @Override
        public int hashCode() {
            int result = struct.hashCode();
            result = 31 * result + type.hashCode();

            return result;
        }
    }

    public static final class Constructor {
        public final String name;
        public final Struct owner;
        public final List<Type> arguments;
        public final org.objectweb.asm.commons.Method method;
        public final java.lang.reflect.Constructor<?> reflect;

        private Constructor(final String name, final Struct owner, final List<Type> arguments,
                            final org.objectweb.asm.commons.Method method, final java.lang.reflect.Constructor<?> reflect) {
            this.name = name;
            this.owner = owner;
            this.arguments = Collections.unmodifiableList(arguments);
            this.method = method;
            this.reflect = reflect;
        }
    }

    public static class Method {
        public final String name;
        public final Struct owner;
        public final Type rtn;
        public final List<Type> arguments;
        public final org.objectweb.asm.commons.Method method;
        public final java.lang.reflect.Method reflect;
        public final MethodHandle handle;

        private Method(final String name, final Struct owner, final Type rtn, final List<Type> arguments,
                       final org.objectweb.asm.commons.Method method, final java.lang.reflect.Method reflect,
                       final MethodHandle handle) {
            this.name = name;
            this.owner = owner;
            this.rtn = rtn;
            this.arguments = Collections.unmodifiableList(arguments);
            this.method = method;
            this.reflect = reflect;
            this.handle = handle;
        }
    }

    public static final class Field {
        public final String name;
        public final Struct owner;
        public final Type generic;
        public final Type type;
        public final java.lang.reflect.Field reflect;
        public final MethodHandle getter;
        public final MethodHandle setter;

        private Field(final String name, final Struct owner, final Type generic, final Type type,
                      final java.lang.reflect.Field reflect, final MethodHandle getter, final MethodHandle setter) {
            this.name = name;
            this.owner = owner;
            this.generic = generic;
            this.type = type;
            this.reflect = reflect;
            this.getter = getter;
            this.setter = setter;
        }
    }

    // TODO: instead of hashing on this, we could have a 'next' pointer in Method itself, but it would make code more complex
    // please do *NOT* under any circumstances change this to be the crappy Tuple from elasticsearch!
    /**
     * Key for looking up a method.
     * <p>
     * Methods are keyed on both name and arity, and can be overloaded once per arity.
     * This allows signatures such as {@code String.indexOf(String) vs String.indexOf(String, int)}.
     * <p>
     * It is less flexible than full signature overloading where types can differ too, but
     * better than just the name, and overloading types adds complexity to users, too.
     */
    public static final class MethodKey {
        public final String name;
        public final int arity;

        /**
         * Create a new lookup key
         * @param name name of the method
         * @param arity number of parameters
         */
        public MethodKey(String name, int arity) {
            this.name = Objects.requireNonNull(name);
            this.arity = arity;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + arity;
            result = prime * result + name.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            MethodKey other = (MethodKey) obj;
            if (arity != other.arity) return false;
            if (!name.equals(other.name)) return false;
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            sb.append('/');
            sb.append(arity);
            return sb.toString();
        }
    }

    public static final class Struct {
        public final String name;
        public final Class<?> clazz;
        public final org.objectweb.asm.Type type;

        public final Map<MethodKey, Constructor> constructors;
        public final Map<MethodKey, Method> staticMethods;
        public final Map<MethodKey, Method> methods;

        public final Map<String, Field> staticMembers;
        public final Map<String, Field> members;

        private Struct(final String name, final Class<?> clazz, final org.objectweb.asm.Type type) {
            this.name = name;
            this.clazz = clazz;
            this.type = type;

            constructors = new HashMap<>();
            staticMethods = new HashMap<>();
            methods = new HashMap<>();

            staticMembers = new HashMap<>();
            members = new HashMap<>();
        }

        private Struct(final Struct struct) {
            name = struct.name;
            clazz = struct.clazz;
            type = struct.type;

            constructors = Collections.unmodifiableMap(struct.constructors);
            staticMethods = Collections.unmodifiableMap(struct.staticMethods);
            methods = Collections.unmodifiableMap(struct.methods);

            staticMembers = Collections.unmodifiableMap(struct.staticMembers);
            members = Collections.unmodifiableMap(struct.members);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            Struct struct = (Struct)object;

            return name.equals(struct.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static class Cast {
        public final Type from;
        public final Type to;
        public final boolean explicit;

        public Cast(final Type from, final Type to, final boolean explicit) {
            this.from = from;
            this.to = to;
            this.explicit = explicit;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }

            if (object == null || getClass() != object.getClass()) {
                return false;
            }

            final Cast cast = (Cast)object;

            return from.equals(cast.from) && to.equals(cast.to) && explicit == cast.explicit;
        }

        @Override
        public int hashCode() {
            int result = from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + (explicit ? 1 : 0);

            return result;
        }
    }

    public static final class Transform extends Cast {
        public final Method method;
        public final Type upcast;
        public final Type downcast;

        public Transform(final Cast cast, Method method, final Type upcast, final Type downcast) {
            super(cast.from, cast.to, cast.explicit);

            this.method = method;
            this.upcast = upcast;
            this.downcast = downcast;
        }
    }

    public static final class RuntimeClass {
        public final Map<MethodKey, Method> methods;
        public final Map<String, MethodHandle> getters;
        public final Map<String, MethodHandle> setters;

        private RuntimeClass(final Map<MethodKey, Method> methods,
                             final Map<String, MethodHandle> getters, final Map<String, MethodHandle> setters) {
            this.methods = methods;
            this.getters = getters;
            this.setters = setters;
        }
    }

    public final Map<String, Struct> structsMap;
    public final Map<Cast, Cast> transformsMap;
    public final Map<Class<?>, RuntimeClass> runtimeMap;

    public final Type voidType;
    public final Type booleanType;
    public final Type byteType;
    public final Type shortType;
    public final Type charType;
    public final Type intType;
    public final Type longType;
    public final Type floatType;
    public final Type doubleType;

    public final Type voidobjType;
    public final Type booleanobjType;
    public final Type byteobjType;
    public final Type shortobjType;
    public final Type charobjType;
    public final Type intobjType;
    public final Type longobjType;
    public final Type floatobjType;
    public final Type doubleobjType;

    public final Type objectType;
    public final Type defType;
    public final Type numberType;
    public final Type charseqType;
    public final Type stringType;
    public final Type mathType;
    public final Type utilityType;
    public final Type defobjType;

    public final Type itrType;
    public final Type oitrType;
    public final Type sitrType;

    public final Type collectionType;
    public final Type ocollectionType;
    public final Type scollectionType;

    public final Type listType;
    public final Type arraylistType;
    public final Type olistType;
    public final Type oarraylistType;
    public final Type slistType;
    public final Type sarraylistType;

    public final Type setType;
    public final Type hashsetType;
    public final Type osetType;
    public final Type ohashsetType;
    public final Type ssetType;
    public final Type shashsetType;

    public final Type mapType;
    public final Type hashmapType;
    public final Type oomapType;
    public final Type oohashmapType;
    public final Type smapType;
    public final Type shashmapType;
    public final Type somapType;
    public final Type sohashmapType;

    public final Type execType;

    public final Type exceptionType;
    public final Type arithexcepType;
    public final Type iargexcepType;
    public final Type istateexceptType;
    public final Type nfexcepType;

    // docvalues accessors
    public final Type geoPointType;
    public final Type stringsType;
    // TODO: add ReadableDateTime? or don't expose the joda stuff?
    public final Type longsType;
    public final Type doublesType;
    public final Type geoPointsType;

    // for testing features not currently "used" by the whitelist (we should not rush the API for that!)
    public final Type featureTestType;

    private Definition() {
        structsMap = new HashMap<>();
        transformsMap = new HashMap<>();
        runtimeMap = new HashMap<>();

        addStructs();

        voidType = getType("void");
        booleanType = getType("boolean");
        byteType = getType("byte");
        shortType = getType("short");
        charType = getType("char");
        intType = getType("int");
        longType = getType("long");
        floatType = getType("float");
        doubleType = getType("double");

        voidobjType = getType("Void");
        booleanobjType = getType("Boolean");
        byteobjType = getType("Byte");
        shortobjType = getType("Short");
        charobjType = getType("Character");
        intobjType = getType("Integer");
        longobjType = getType("Long");
        floatobjType = getType("Float");
        doubleobjType = getType("Double");

        objectType = getType("Object");
        defType = getType("def");
        numberType = getType("Number");
        charseqType = getType("CharSequence");
        stringType = getType("String");
        mathType = getType("Math");
        utilityType = getType("Utility");
        defobjType = getType("Def");

        itrType = getType("Iterator");
        oitrType = getType("Iterator<Object>");
        sitrType = getType("Iterator<String>");

        collectionType = getType("Collection");
        ocollectionType = getType("Collection<Object>");
        scollectionType = getType("Collection<String>");

        listType = getType("List");
        arraylistType = getType("ArrayList");
        olistType = getType("List<Object>");
        oarraylistType = getType("ArrayList<Object>");
        slistType = getType("List<String>");
        sarraylistType = getType("ArrayList<String>");

        setType = getType("Set");
        hashsetType = getType("HashSet");
        osetType = getType("Set<Object>");
        ohashsetType = getType("HashSet<Object>");
        ssetType = getType("Set<String>");
        shashsetType = getType("HashSet<String>");

        mapType = getType("Map");
        hashmapType = getType("HashMap");
        oomapType = getType("Map<Object,Object>");
        oohashmapType = getType("HashMap<Object,Object>");
        smapType = getType("Map<String,def>");
        shashmapType = getType("HashMap<String,def>");
        somapType = getType("Map<String,Object>");
        sohashmapType = getType("HashMap<String,Object>");

        execType = getType("Executable");

        exceptionType = getType("Exception");
        arithexcepType = getType("ArithmeticException");
        iargexcepType = getType("IllegalArgumentException");
        istateexceptType = getType("IllegalStateException");
        nfexcepType = getType("NumberFormatException");

        geoPointType = getType("GeoPoint");
        stringsType = getType("Strings");
        longsType = getType("Longs");
        doublesType = getType("Doubles");
        geoPointsType = getType("GeoPoints");

        featureTestType = getType("FeatureTest");

        addElements();
        copyStructs();
        addTransforms();
        addRuntimeClasses();
    }

    private Definition(final Definition definition) {
        final Map<String, Struct> structs = new HashMap<>();

        for (final Struct struct : definition.structsMap.values()) {
            structs.put(struct.name, new Struct(struct));
        }

        this.structsMap = Collections.unmodifiableMap(structs);
        this.transformsMap = Collections.unmodifiableMap(definition.transformsMap);
        this.runtimeMap = Collections.unmodifiableMap(definition.runtimeMap);

        this.voidType = definition.voidType;
        this.booleanType = definition.booleanType;
        this.byteType = definition.byteType;
        this.shortType = definition.shortType;
        this.charType = definition.charType;
        this.intType = definition.intType;
        this.longType = definition.longType;
        this.floatType = definition.floatType;
        this.doubleType = definition.doubleType;

        this.voidobjType = definition.voidobjType;
        this.booleanobjType = definition.booleanobjType;
        this.byteobjType = definition.byteobjType;
        this.shortobjType = definition.shortobjType;
        this.charobjType = definition.charobjType;
        this.intobjType = definition.intobjType;
        this.longobjType = definition.longobjType;
        this.floatobjType = definition.floatobjType;
        this.doubleobjType = definition.doubleobjType;

        this.objectType = definition.objectType;
        this.defType = definition.defType;
        this.numberType = definition.numberType;
        this.charseqType = definition.charseqType;
        this.stringType = definition.stringType;
        this.mathType = definition.mathType;
        this.utilityType = definition.utilityType;
        this.defobjType = definition.defobjType;

        this.itrType = definition.itrType;
        this.oitrType = definition.oitrType;
        this.sitrType = definition.sitrType;

        this.collectionType = definition.collectionType;
        this.ocollectionType = definition.ocollectionType;
        this.scollectionType = definition.scollectionType;

        this.listType = definition.listType;
        this.arraylistType = definition.arraylistType;
        this.olistType = definition.olistType;
        this.oarraylistType = definition.oarraylistType;
        this.slistType = definition.slistType;
        this.sarraylistType = definition.sarraylistType;

        this.setType = definition.setType;
        this.hashsetType = definition.hashsetType;
        this.osetType = definition.osetType;
        this.ohashsetType = definition.ohashsetType;
        this.ssetType = definition.ssetType;
        this.shashsetType = definition.shashsetType;

        this.mapType = definition.mapType;
        this.hashmapType = definition.hashmapType;
        this.oomapType = definition.oomapType;
        this.oohashmapType = definition.oohashmapType;
        this.smapType = definition.smapType;
        this.shashmapType = definition.shashmapType;
        this.somapType = definition.somapType;
        this.sohashmapType = definition.sohashmapType;

        this.execType = definition.execType;

        this.exceptionType = definition.exceptionType;
        this.arithexcepType = definition.arithexcepType;
        this.iargexcepType = definition.iargexcepType;
        this.istateexceptType = definition.istateexceptType;
        this.nfexcepType = definition.nfexcepType;

        this.geoPointType = definition.geoPointType;
        this.stringsType = definition.stringsType;
        this.longsType = definition.longsType;
        this.doublesType = definition.doublesType;
        this.geoPointsType = definition.geoPointsType;

        this.featureTestType = definition.featureTestType;
    }

    private void addStructs() {
        addStruct( "void"    , void.class    );
        addStruct( "boolean" , boolean.class );
        addStruct( "byte"    , byte.class    );
        addStruct( "short"   , short.class   );
        addStruct( "char"    , char.class    );
        addStruct( "int"     , int.class     );
        addStruct( "long"    , long.class    );
        addStruct( "float"   , float.class   );
        addStruct( "double"  , double.class  );

        addStruct( "Void"      , Void.class      );
        addStruct( "Boolean"   , Boolean.class   );
        addStruct( "Byte"      , Byte.class      );
        addStruct( "Short"     , Short.class     );
        addStruct( "Character" , Character.class );
        addStruct( "Integer"   , Integer.class   );
        addStruct( "Long"      , Long.class      );
        addStruct( "Float"     , Float.class     );
        addStruct( "Double"    , Double.class    );

        addStruct( "Object"       , Object.class       );
        addStruct( "def"          , Object.class       );
        addStruct( "Number"       , Number.class       );
        addStruct( "CharSequence" , CharSequence.class );
        addStruct( "String"       , String.class       );
        addStruct( "Math"         , Math.class         );
        addStruct( "Utility"      , Utility.class      );
        addStruct( "Def"          , Def.class          );

        addStruct( "Iterator"         , Iterator.class );
        addStruct( "Iterator<Object>" , Iterator.class );
        addStruct( "Iterator<String>" , Iterator.class );

        addStruct( "Collection"         , Collection.class );
        addStruct( "Collection<Object>" , Collection.class );
        addStruct( "Collection<String>" , Collection.class );

        addStruct( "List"              , List.class      );
        addStruct( "ArrayList"         , ArrayList.class );
        addStruct( "List<Object>"      , List.class      );
        addStruct( "ArrayList<Object>" , ArrayList.class );
        addStruct( "List<String>"      , List.class      );
        addStruct( "ArrayList<String>" , ArrayList.class );

        addStruct( "Set"             , Set.class     );
        addStruct( "HashSet"         , HashSet.class );
        addStruct( "Set<Object>"     , Set.class     );
        addStruct( "HashSet<Object>" , HashSet.class );
        addStruct( "Set<String>"     , Set.class     );
        addStruct( "HashSet<String>" , HashSet.class );

        addStruct( "Map"                    , Map.class     );
        addStruct( "HashMap"                , HashMap.class );
        addStruct( "Map<Object,Object>"     , Map.class     );
        addStruct( "HashMap<Object,Object>" , HashMap.class );
        addStruct( "Map<String,def>"        , Map.class     );
        addStruct( "HashMap<String,def>"    , HashMap.class );
        addStruct( "Map<String,Object>"     , Map.class     );
        addStruct( "HashMap<String,Object>" , HashMap.class );

        addStruct( "Executable" , Executable.class );

        addStruct( "Exception"                , Exception.class);
        addStruct( "ArithmeticException"      , ArithmeticException.class);
        addStruct( "IllegalArgumentException" , IllegalArgumentException.class);
        addStruct( "IllegalStateException"    , IllegalStateException.class);
        addStruct( "NumberFormatException"    , NumberFormatException.class);

        addStruct( "GeoPoint"  , GeoPoint.class);
        addStruct( "Strings"   , ScriptDocValues.Strings.class);
        addStruct( "Longs"     , ScriptDocValues.Longs.class);
        addStruct( "Doubles"   , ScriptDocValues.Doubles.class);
        addStruct( "GeoPoints" , ScriptDocValues.GeoPoints.class);

        addStruct( "FeatureTest", FeatureTest.class);
    }

    private void addElements() {
        addMethod("Object", "equals", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Object", "hashCode", null, false, intType, new Type[] {}, null, null);
        addMethod("Object", "toString", null, false, stringType, new Type[] {}, null, null);

        addMethod("def", "equals", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("def", "hashCode", null, false, intType, new Type[] {}, null, null);
        addMethod("def", "toString", null, false, stringType, new Type[] {}, null, null);

        addConstructor("Boolean", "new", new Type[] {booleanType}, null);
        addMethod("Boolean", "booleanValue", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Boolean", "compare", null, true, intType, new Type[] {booleanType,booleanType}, null, null);
        addMethod("Boolean", "compareTo", null, false, intType, new Type[] {booleanobjType}, null, null);
        addMethod("Boolean", "parseBoolean", null, true, booleanType, new Type[] {stringType}, null, null);
        addMethod("Boolean", "valueOf", null, true, booleanobjType, new Type[] {booleanType}, null, null);
        addField("Boolean", "FALSE", null, true, booleanobjType, null);
        addField("Boolean", "TRUE", null, true, booleanobjType, null);

        addConstructor("Byte", "new", new Type[] {byteType}, null);
        addMethod("Byte", "compare", null, true, intType, new Type[] {byteType,byteType}, null, null);
        addMethod("Byte", "compareTo", null, false, intType, new Type[] {byteobjType}, null, null);
        addMethod("Byte", "parseByte", null, true, byteType, new Type[] {stringType}, null, null);
        addMethod("Byte", "valueOf", null, true, byteobjType, new Type[] {byteType}, null, null);
        addField("Byte", "MIN_VALUE", null, true, byteType, null);
        addField("Byte", "MAX_VALUE", null, true, byteType, null);

        addConstructor("Short", "new", new Type[] {shortType}, null);
        addMethod("Short", "compare", null, true, intType, new Type[] {shortType,shortType}, null, null);
        addMethod("Short", "compareTo", null, false, intType, new Type[] {shortobjType}, null, null);
        addMethod("Short", "parseShort", null, true, shortType, new Type[] {stringType}, null, null);
        addMethod("Short", "valueOf", null, true, shortobjType, new Type[] {shortType}, null, null);
        addField("Short", "MIN_VALUE", null, true, shortType, null);
        addField("Short", "MAX_VALUE", null, true, shortType, null);

        addConstructor("Character", "new", new Type[] {charType}, null);
        addMethod("Character", "charCount", null, true, intType, new Type[] {intType}, null, null);
        addMethod("Character", "charValue", null, false, charType, new Type[] {}, null, null);
        addMethod("Character", "compare", null, true, intType, new Type[] {charType,charType}, null, null);
        addMethod("Character", "compareTo", null, false, intType, new Type[] {charobjType}, null, null);
        addMethod("Character", "digit", null, true, intType, new Type[] {intType,intType}, null, null);
        addMethod("Character", "forDigit", null, true, charType, new Type[] {intType,intType}, null, null);
        addMethod("Character", "getName", null, true, stringType, new Type[] {intType}, null, null);
        addMethod("Character", "getNumericValue", null, true, intType, new Type[] {intType}, null, null);
        addMethod("Character", "isAlphabetic", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isDefined", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isDigit", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isIdeographic", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isLetter", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isLetterOrDigit", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isLowerCase", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isMirrored", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isSpaceChar", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isTitleCase", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isUpperCase", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "isWhitespace", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Character", "valueOf", null, true, charobjType, new Type[] {charType}, null, null);
        addField("Character", "MIN_VALUE", null, true, charType, null);
        addField("Character", "MAX_VALUE", null, true, charType, null);

        addConstructor("Integer", "new", new Type[] {intType}, null);
        addMethod("Integer", "compare", null, true, intType, new Type[] {intType,intType}, null, null);
        addMethod("Integer", "compareTo", null, false, intType, new Type[] {intobjType}, null, null);
        addMethod("Integer", "min", null, true, intType, new Type[] {intType,intType}, null, null);
        addMethod("Integer", "max", null, true, intType, new Type[] {intType,intType}, null, null);
        addMethod("Integer", "parseInt", null, true, intType, new Type[] {stringType}, null, null);
        addMethod("Integer", "signum", null, true, intType, new Type[] {intType}, null, null);
        addMethod("Integer", "toHexString", null, true, stringType, new Type[] {intType}, null, null);
        addMethod("Integer", "valueOf", null, true, intobjType, new Type[] {intType}, null, null);
        addField("Integer", "MIN_VALUE", null, true, intType, null);
        addField("Integer", "MAX_VALUE", null, true, intType, null);

        addConstructor("Long", "new", new Type[] {longType}, null);
        addMethod("Long", "compare", null, true, intType, new Type[] {longType,longType}, null, null);
        addMethod("Long", "compareTo", null, false, intType, new Type[] {longobjType}, null, null);
        addMethod("Long", "min", null, true, longType, new Type[] {longType,longType}, null, null);
        addMethod("Long", "max", null, true, longType, new Type[] {longType,longType}, null, null);
        addMethod("Long", "parseLong", null, true, longType, new Type[] {stringType}, null, null);
        addMethod("Long", "signum", null, true, intType, new Type[] {longType}, null, null);
        addMethod("Long", "toHexString", null, true, stringType, new Type[] {longType}, null, null);
        addMethod("Long", "valueOf", null, true, longobjType, new Type[] {longType}, null, null);
        addField("Long", "MIN_VALUE", null, true, longType, null);
        addField("Long", "MAX_VALUE", null, true, longType, null);

        addConstructor("Float", "new", new Type[] {floatType}, null);
        addMethod("Float", "compare", null, true, intType, new Type[] {floatType,floatType}, null, null);
        addMethod("Float", "compareTo", null, false, intType, new Type[] {floatobjType}, null, null);
        addMethod("Float", "min", null, true, floatType, new Type[] {floatType,floatType}, null, null);
        addMethod("Float", "max", null, true, floatType, new Type[] {floatType,floatType}, null, null);
        addMethod("Float", "parseFloat", null, true, floatType, new Type[] {stringType}, null, null);
        addMethod("Float", "toHexString", null, true, stringType, new Type[] {floatType}, null, null);
        addMethod("Float", "valueOf", null, true, floatobjType, new Type[] {floatType}, null, null);
        addField("Float", "MIN_VALUE", null, true, floatType, null);
        addField("Float", "MAX_VALUE", null, true, floatType, null);

        addConstructor("Double", "new", new Type[] {doubleType}, null);
        addMethod("Double", "compare", null, true, intType, new Type[] {doubleType,doubleType}, null, null);
        addMethod("Double", "compareTo", null, false, intType, new Type[] {doubleobjType}, null, null);
        addMethod("Double", "min", null, true, doubleType, new Type[] {doubleType,doubleType}, null, null);
        addMethod("Double", "max", null, true, doubleType, new Type[] {doubleType,doubleType}, null, null);
        addMethod("Double", "parseDouble", null, true, doubleType, new Type[] {stringType}, null, null);
        addMethod("Double", "toHexString", null, true, stringType, new Type[] {doubleType}, null, null);
        addMethod("Double", "valueOf", null, true, doubleobjType, new Type[] {doubleType}, null, null);
        addField("Double", "MIN_VALUE", null, true, doubleType, null);
        addField("Double", "MAX_VALUE", null, true, doubleType, null);

        addMethod("Number", "byteValue", null, false, byteType, new Type[] {}, null, null);
        addMethod("Number", "shortValue", null, false, shortType, new Type[] {}, null, null);
        addMethod("Number", "intValue", null, false, intType, new Type[] {}, null, null);
        addMethod("Number", "longValue", null, false, longType, new Type[] {}, null, null);
        addMethod("Number", "floatValue", null, false, floatType, new Type[] {}, null, null);
        addMethod("Number", "doubleValue", null, false, doubleType, new Type[] {}, null, null);

        addMethod("CharSequence", "charAt", null, false, charType, new Type[] {intType}, null, null);
        addMethod("CharSequence", "length", null, false, intType, new Type[] {}, null, null);

        addConstructor("String", "new", new Type[] {}, null);
        addMethod("String", "codePointAt", null, false, intType, new Type[] {intType}, null, null);
        addMethod("String", "compareTo", null, false, intType, new Type[] {stringType}, null, null);
        addMethod("String", "concat", null, false, stringType, new Type[] {stringType}, null, null);
        addMethod("String", "endsWith", null, false, booleanType, new Type[] {stringType}, null, null);
        addMethod("String", "indexOf", null, false, intType, new Type[] {stringType}, null, null);
        addMethod("String", "indexOf", null, false, intType, new Type[] {stringType, intType}, null, null);
        addMethod("String", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("String", "replace", null, false, stringType, new Type[] {charseqType, charseqType}, null, null);
        addMethod("String", "startsWith", null, false, booleanType, new Type[] {stringType}, null, null);
        addMethod("String", "substring", null, false, stringType, new Type[] {intType, intType}, null, null);
        addMethod("String", "toCharArray", null, false, getType(charType.struct, 1), new Type[] {}, null, null);
        addMethod("String", "trim", null, false, stringType, new Type[] {}, null, null);

        addMethod("Utility", "NumberToboolean", null, true, booleanType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberTochar", null, true, charType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToBoolean", null, true, booleanobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToByte", null, true, byteobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToShort", null, true, shortobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToCharacter", null, true, charobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToInteger", null, true, intobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToLong", null, true, longobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToFloat", null, true, floatobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "NumberToDouble", null, true, doubleobjType, new Type[] {numberType}, null, null);
        addMethod("Utility", "booleanTobyte", null, true, byteType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToshort", null, true, shortType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTochar", null, true, charType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToint", null, true, intType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTolong", null, true, longType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTofloat", null, true, floatType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanTodouble", null, true, doubleType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "booleanToInteger", null, true, intobjType, new Type[] {booleanType}, null, null);
        addMethod("Utility", "BooleanTobyte", null, true, byteType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToshort", null, true, shortType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTochar", null, true, charType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToint", null, true, intType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTolong", null, true, longType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTofloat", null, true, floatType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanTodouble", null, true, doubleType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToByte", null, true, byteobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToShort", null, true, shortobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToCharacter", null, true, charobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToInteger", null, true, intobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToLong", null, true, longobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToFloat", null, true, floatobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "BooleanToDouble", null, true, doubleobjType, new Type[] {booleanobjType}, null, null);
        addMethod("Utility", "byteToboolean", null, true, booleanType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToShort", null, true, shortobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToCharacter", null, true, charobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToInteger", null, true, intobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToLong", null, true, longobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToFloat", null, true, floatobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "byteToDouble", null, true, doubleobjType, new Type[] {byteType}, null, null);
        addMethod("Utility", "ByteToboolean", null, true, booleanType, new Type[] {byteobjType}, null, null);
        addMethod("Utility", "ByteTochar", null, true, charType, new Type[] {byteobjType}, null, null);
        addMethod("Utility", "shortToboolean", null, true, booleanType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToByte", null, true, byteobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToCharacter", null, true, charobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToInteger", null, true, intobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToLong", null, true, longobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToFloat", null, true, floatobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "shortToDouble", null, true, doubleobjType, new Type[] {shortType}, null, null);
        addMethod("Utility", "ShortToboolean", null, true, booleanType, new Type[] {shortobjType}, null, null);
        addMethod("Utility", "ShortTochar", null, true, charType, new Type[] {shortobjType}, null, null);
        addMethod("Utility", "charToboolean", null, true, booleanType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToByte", null, true, byteobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToShort", null, true, shortobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToInteger", null, true, intobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToLong", null, true, longobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToFloat", null, true, floatobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToDouble", null, true, doubleobjType, new Type[] {charType}, null, null);
        addMethod("Utility", "charToString", null, true, stringType, new Type[] {charType}, null, null);
        addMethod("Utility", "CharacterToboolean", null, true, booleanType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTobyte", null, true, byteType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToshort", null, true, shortType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToint", null, true, intType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTolong", null, true, longType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTofloat", null, true, floatType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterTodouble", null, true, doubleType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToBoolean", null, true, booleanobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToByte", null, true, byteobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToShort", null, true, shortobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToInteger", null, true, intobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToLong", null, true, longobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToFloat", null, true, floatobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToDouble", null, true, doubleobjType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "CharacterToString", null, true, stringType, new Type[] {charobjType}, null, null);
        addMethod("Utility", "intToboolean", null, true, booleanType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToByte", null, true, byteobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToShort", null, true, shortobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToCharacter", null, true, charobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToLong", null, true, longobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToFloat", null, true, floatobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "intToDouble", null, true, doubleobjType, new Type[] {intType}, null, null);
        addMethod("Utility", "IntegerToboolean", null, true, booleanType, new Type[] {intobjType}, null, null);
        addMethod("Utility", "IntegerTochar", null, true, charType, new Type[] {intobjType}, null, null);
        addMethod("Utility", "longToboolean", null, true, booleanType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToByte", null, true, byteobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToShort", null, true, shortobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToCharacter", null, true, charobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToInteger", null, true, intobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToFloat", null, true, floatobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "longToDouble", null, true, doubleobjType, new Type[] {longType}, null, null);
        addMethod("Utility", "LongToboolean", null, true, booleanType, new Type[] {longobjType}, null, null);
        addMethod("Utility", "LongTochar", null, true, charType, new Type[] {longobjType}, null, null);
        addMethod("Utility", "floatToboolean", null, true, booleanType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToByte", null, true, byteobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToShort", null, true, shortobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToCharacter", null, true, charobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToInteger", null, true, intobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToLong", null, true, longobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "floatToDouble", null, true, doubleobjType, new Type[] {floatType}, null, null);
        addMethod("Utility", "FloatToboolean", null, true, booleanType, new Type[] {floatobjType}, null, null);
        addMethod("Utility", "FloatTochar", null, true, charType, new Type[] {floatobjType}, null, null);
        addMethod("Utility", "doubleToboolean", null, true, booleanType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToByte", null, true, byteobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToShort", null, true, shortobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToCharacter", null, true, charobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToInteger", null, true, intobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToLong", null, true, longobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "doubleToFloat", null, true, floatobjType, new Type[] {doubleType}, null, null);
        addMethod("Utility", "DoubleToboolean", null, true, booleanType, new Type[] {doubleobjType}, null, null);
        addMethod("Utility", "DoubleTochar", null, true, charType, new Type[] {doubleobjType}, null, null);
        addMethod("Utility", "StringTochar", null, true, charType, new Type[] {stringType}, null, null);
        addMethod("Utility", "StringToCharacter", null, true, charobjType, new Type[] {stringType}, null, null);

        addMethod("Math", "abs", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "acos", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "asin", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "atan", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "atan2", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "cbrt", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "ceil", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "cos", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "cosh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "exp", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "expm1", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "floor", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "hypot", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "log", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "log10", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "log1p", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "max", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "min", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "pow", null, true, doubleType, new Type[] {doubleType, doubleType}, null, null);
        addMethod("Math", "random", null, true, doubleType, new Type[] {}, null, null);
        addMethod("Math", "rint", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "round", null, true, longType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sin", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sinh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "sqrt", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "tan", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "tanh", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "toDegrees", null, true, doubleType, new Type[] {doubleType}, null, null);
        addMethod("Math", "toRadians", null, true, doubleType, new Type[] {doubleType}, null, null);
        addField("Math", "E", null, true, doubleType, null);
        addField("Math", "PI", null, true, doubleType, null);

        addMethod("Def", "DefTobyteImplicit", null, true, byteType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToshortImplicit", null, true, shortType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTocharImplicit", null, true, charType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTointImplicit", null, true, intType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTolongImplicit", null, true, longType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTofloatImplicit", null, true, floatType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTodoubleImplicit", null, true, doubleType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToByteImplicit", null, true, byteobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToShortImplicit", null, true, shortobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToCharacterImplicit", null, true, charobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToIntegerImplicit", null, true, intobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToLongImplicit", null, true, longobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToFloatImplicit", null, true, floatobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToDoubleImplicit", null, true, doubleobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTobyteExplicit", null, true, byteType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToshortExplicit", null, true, shortType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTocharExplicit", null, true, charType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTointExplicit", null, true, intType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTolongExplicit", null, true, longType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTofloatExplicit", null, true, floatType, new Type[] {defType}, null, null);
        addMethod("Def", "DefTodoubleExplicit", null, true, doubleType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToByteExplicit", null, true, byteobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToShortExplicit", null, true, shortobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToCharacterExplicit", null, true, charobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToIntegerExplicit", null, true, intobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToLongExplicit", null, true, longobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToFloatExplicit", null, true, floatobjType, new Type[] {defType}, null, null);
        addMethod("Def", "DefToDoubleExplicit", null, true, doubleobjType, new Type[] {defType}, null, null);

        addMethod("Iterator", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator", "next", null, false, objectType, new Type[] {}, defType, null);
        addMethod("Iterator", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Iterator<Object>", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator<Object>", "next", null, false, objectType, new Type[] {}, null, null);
        addMethod("Iterator<Object>", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Iterator<String>", "hasNext", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Iterator<String>", "next", null, false, objectType, new Type[] {}, stringType, null);
        addMethod("Iterator<String>", "remove", null, false, voidType, new Type[] {}, null, null);

        addMethod("Collection", "add", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection", "contains", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection", "iterator", null, false, itrType, new Type[] {}, null, null);
        addMethod("Collection", "remove", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Collection", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("Collection<Object>", "add", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "contains", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "iterator", null, false, oitrType, new Type[] {}, null, null);
        addMethod("Collection<Object>", "remove", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Collection<Object>", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("Collection<String>", "add", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "clear", null, false, voidType, new Type[] {}, null, null);
        addMethod("Collection<String>", "contains", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Collection<String>", "iterator", null, false, sitrType, new Type[] {}, null, null);
        addMethod("Collection<String>", "remove", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Collection<String>", "size", null, false, intType, new Type[] {}, null, null);

        addMethod("List", "set", null, false, objectType, new Type[] {intType, objectType}, defType, new Type[] {intType, defType});
        addMethod("List", "get", null, false, objectType, new Type[] {intType}, defType, null);
        addMethod("List", "remove", null, false, objectType, new Type[] {intType}, defType, null);
        addMethod("List", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList", "new", new Type[] {}, null);

        addMethod("List<Object>", "set", null, false, objectType, new Type[] {intType, objectType}, null, null);
        addMethod("List<Object>", "get", null, false, objectType, new Type[] {intType}, null, null);
        addMethod("List<Object>", "remove", null, false, objectType, new Type[] {intType}, null, null);
        addMethod("List<Object>", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList<Object>", "new", new Type[] {}, null);

        addMethod("List<String>", "set", null, false, objectType, new Type[] {intType, objectType}, stringType,
            new Type[] {intType, stringType});
        addMethod("List<String>", "get", null, false, objectType, new Type[] {intType}, stringType, null);
        addMethod("List<String>", "remove", null, false, objectType, new Type[] {intType}, stringType, null);
        addMethod("List<String>", "getLength", "size", false, intType, new Type[] {}, null, null);

        addConstructor("ArrayList<String>", "new", new Type[] {}, null);

        addConstructor("HashSet", "new", new Type[] {}, null);

        addConstructor("HashSet<Object>", "new", new Type[] {}, null);

        addConstructor("HashSet<String>", "new", new Type[] {}, null);

        addMethod("Map", "put", null, false, objectType, new Type[] {objectType, objectType}, defType, new Type[] {defType, defType});
        addMethod("Map", "get", null, false, objectType, new Type[] {objectType}, defType, new Type[] {defType});
        addMethod("Map", "remove", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map", "containsValue", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map", "keySet", null, false, osetType, new Type[] {}, setType, null);
        addMethod("Map", "values", null, false, ocollectionType, new Type[] {}, collectionType, null);

        addConstructor("HashMap", "new", new Type[] {}, null);

        addMethod("Map<Object,Object>", "put", null, false, objectType, new Type[] {objectType, objectType}, null, null);
        addMethod("Map<Object,Object>", "get", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "remove", null, false, objectType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<Object,Object>", "keySet", null, false, osetType, new Type[] {}, null, null);
        addMethod("Map<Object,Object>", "values", null, false, ocollectionType, new Type[] {}, null, null);

        addConstructor("HashMap<Object,Object>", "new", new Type[] {}, null);

        addMethod("Map<String,def>", "put", null, false, objectType, new Type[] {objectType, objectType}, defType,
            new Type[] {stringType, defType});
        addMethod("Map<String,def>", "get", null, false, objectType, new Type[] {objectType}, defType, new Type[] {stringType});
        addMethod("Map<String,def>", "remove", null, false, objectType, new Type[] {objectType}, defType, new Type[] {stringType});
        addMethod("Map<String,def>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<String,def>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<String,def>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,def>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, new Type[] {defType});
        addMethod("Map<String,def>", "keySet", null, false, osetType, new Type[] {}, ssetType, null);
        addMethod("Map<String,def>", "values", null, false, ocollectionType, new Type[] {}, collectionType, null);

        addConstructor("HashMap<String,def>", "new", new Type[] {}, null);

        addMethod("Map<String,Object>", "put", null, false, objectType, new Type[] {objectType, objectType}, null,
            new Type[] {stringType, objectType});
        addMethod("Map<String,Object>", "get", null, false, objectType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "remove", null, false, objectType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "isEmpty", null, false, booleanType, new Type[] {}, null, null);
        addMethod("Map<String,Object>", "size", null, false, intType, new Type[] {}, null, null);
        addMethod("Map<String,Object>", "containsKey", null, false, booleanType, new Type[] {objectType}, null, new Type[] {stringType});
        addMethod("Map<String,Object>", "containsValue", null, false, booleanType, new Type[] {objectType}, null, null);
        addMethod("Map<String,Object>", "keySet", null, false, osetType, new Type[] {}, ssetType, null);
        addMethod("Map<String,Object>", "values", null, false, ocollectionType, new Type[] {}, null, null);

        addConstructor("HashMap<String,Object>", "new", new Type[] {}, null);

        addMethod("Exception", "getMessage", null, false, stringType, new Type[] {}, null, null);

        addConstructor("ArithmeticException", "new", new Type[] {stringType}, null);

        addConstructor("IllegalArgumentException", "new", new Type[] {stringType}, null);

        addConstructor("IllegalStateException", "new", new Type[] {stringType}, null);

        addConstructor("NumberFormatException", "new", new Type[] {stringType}, null);

        addMethod("GeoPoint", "getLat", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoint", "getLon", null, false, doubleType, new Type[] {}, null, null);
        addMethod("Strings", "getValue", null, false, stringType, new Type[] {}, null, null);
        addMethod("Strings", "getValues", null, false, slistType, new Type[] {}, null, null);
        addMethod("Longs", "getValue", null, false, longType, new Type[] {}, null, null);
        addMethod("Longs", "getValues", null, false, olistType, new Type[] {}, null, null);
        // TODO: add better date support for Longs here? (carefully?)
        addMethod("Doubles", "getValue", null, false, doubleType, new Type[] {}, null, null);
        addMethod("Doubles", "getValues", null, false, olistType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getValue", null, false, geoPointType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getValues", null, false, olistType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLat", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLon", null, false, doubleType, new Type[] {}, null, null);
        addMethod("GeoPoints", "getLats", null, false, getType(doubleType.struct, 1), new Type[] {}, null, null);
        addMethod("GeoPoints", "getLons", null, false, getType(doubleType.struct, 1), new Type[] {}, null, null);
        // geo distance functions... so many...
        addMethod("GeoPoints", "factorDistance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistance02", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "factorDistance13", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInKm", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInKmWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInMiles", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "arcDistanceInMilesWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distance", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInKm", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInKmWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInMiles", null, false, doubleType,
                  new Type[] { doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "distanceInMilesWithDefault", null, false, doubleType,
                  new Type[] { doubleType, doubleType, doubleType }, null, null);
        addMethod("GeoPoints", "geohashDistance", null, false, doubleType,
                  new Type[] { stringType }, null, null);
        addMethod("GeoPoints", "geohashDistanceInKm", null, false, doubleType,
                  new Type[] { stringType }, null, null);
        addMethod("GeoPoints", "geohashDistanceInMiles", null, false, doubleType,
                  new Type[] { stringType }, null, null);

        // currently FeatureTest exposes overloaded constructor, field load store, and overloaded static methods
        addConstructor("FeatureTest", "new", new Type[] {}, null);
        addConstructor("FeatureTest", "new", new Type[] {intType, intType}, null);
        addMethod("FeatureTest", "getX", null, false, intType, new Type[] {}, null, null);
        addMethod("FeatureTest", "getY", null, false, intType, new Type[] {}, null, null);
        addMethod("FeatureTest", "setX", null, false, voidType, new Type[] {intType}, null, null);
        addMethod("FeatureTest", "setY", null, false, voidType, new Type[] {intType}, null, null);
        addMethod("FeatureTest", "overloadedStatic", null, true, booleanType, new Type[] {}, null, null);
        addMethod("FeatureTest", "overloadedStatic", null, true, booleanType, new Type[] {booleanType}, null, null);
    }

    private void copyStructs() {
        copyStruct("Void", "Object");
        copyStruct("Boolean", "Object");
        copyStruct("Byte", "Number", "Object");
        copyStruct("Short", "Number", "Object");
        copyStruct("Character", "Object");
        copyStruct("Integer", "Number", "Object");
        copyStruct("Long", "Number", "Object");
        copyStruct("Float", "Number", "Object");
        copyStruct("Double", "Number", "Object");

        copyStruct("Number", "Object");
        copyStruct("CharSequence", "Object");
        copyStruct("String", "CharSequence", "Object");

        copyStruct("List", "Collection", "Object");
        copyStruct("ArrayList", "List", "Collection", "Object");
        copyStruct("List<Object>", "Collection<Object>", "Object");
        copyStruct("ArrayList<Object>", "List<Object>", "Collection<Object>", "Object");
        copyStruct("List<String>", "Collection<String>", "Object");
        copyStruct("ArrayList<String>", "List<String>", "Collection<String>", "Object");

        copyStruct("Set", "Collection", "Object");
        copyStruct("HashSet", "Set", "Collection", "Object");
        copyStruct("Set<Object>", "Collection<Object>", "Object");
        copyStruct("HashSet<Object>", "Set<Object>", "Collection<Object>", "Object");
        copyStruct("Set<String>", "Collection<String>", "Object");
        copyStruct("HashSet<String>", "Set<String>", "Collection<String>", "Object");

        copyStruct("Map", "Object");
        copyStruct("HashMap", "Map", "Object");
        copyStruct("Map<Object,Object>", "Object");
        copyStruct("HashMap<Object,Object>", "Map<Object,Object>", "Object");
        copyStruct("Map<String,def>", "Object");
        copyStruct("HashMap<String,def>", "Map<String,def>", "Object");
        copyStruct("Map<String,Object>", "Object");
        copyStruct("HashMap<String,Object>", "Map<String,Object>", "Object");

        copyStruct("Executable", "Object");

        copyStruct("Exception", "Object");
        copyStruct("ArithmeticException", "Exception", "Object");
        copyStruct("IllegalArgumentException", "Exception", "Object");
        copyStruct("IllegalStateException", "Exception", "Object");
        copyStruct("NumberFormatException", "Exception", "Object");

        copyStruct("GeoPoint", "Object");
        copyStruct("Strings", "List<String>", "Collection<String>", "Object");
        copyStruct("Longs", "List", "Collection", "Object");
        copyStruct("Doubles", "List", "Collection", "Object");
        copyStruct("GeoPoints", "List", "Collection", "Object");

        copyStruct("FeatureTest", "Object");
    }

    private void addTransforms() {
        addTransform(booleanType, objectType, "Boolean", "valueOf", true, false);
        addTransform(booleanType, defType, "Boolean", "valueOf", true, false);
        addTransform(booleanType, booleanobjType, "Boolean", "valueOf", true, false);

        addTransform(byteType, shortType, false);
        addTransform(byteType, charType, true);
        addTransform(byteType, intType, false);
        addTransform(byteType, longType, false);
        addTransform(byteType, floatType, false);
        addTransform(byteType, doubleType, false);
        addTransform(byteType, objectType, "Byte", "valueOf", true, false);
        addTransform(byteType, defType, "Byte", "valueOf", true, false);
        addTransform(byteType, numberType, "Byte", "valueOf", true, false);
        addTransform(byteType, byteobjType, "Byte", "valueOf", true, false);
        addTransform(byteType, shortobjType, "Utility", "byteToShort", true, false);
        addTransform(byteType, charobjType, "Utility", "byteToCharacter", true, true);
        addTransform(byteType, intobjType, "Utility", "byteToInteger", true, false);
        addTransform(byteType, longobjType, "Utility", "byteToLong", true, false);
        addTransform(byteType, floatobjType, "Utility", "byteToFloat", true, false);
        addTransform(byteType, doubleobjType, "Utility", "byteToDouble", true, false);

        addTransform(shortType, byteType, true);
        addTransform(shortType, charType, true);
        addTransform(shortType, intType, false);
        addTransform(shortType, longType, false);
        addTransform(shortType, floatType, false);
        addTransform(shortType, doubleType, false);
        addTransform(shortType, objectType, "Short", "valueOf", true, false);
        addTransform(shortType, defType, "Short", "valueOf", true, false);
        addTransform(shortType, numberType, "Short", "valueOf", true, false);
        addTransform(shortType, byteobjType, "Utility", "shortToByte", true, true);
        addTransform(shortType, shortobjType, "Short", "valueOf", true, false);
        addTransform(shortType, charobjType, "Utility", "shortToCharacter", true, true);
        addTransform(shortType, intobjType, "Utility", "shortToInteger", true, false);
        addTransform(shortType, longobjType, "Utility", "shortToLong", true, false);
        addTransform(shortType, floatobjType, "Utility", "shortToFloat", true, false);
        addTransform(shortType, doubleobjType, "Utility", "shortToDouble", true, false);

        addTransform(charType, byteType, true);
        addTransform(charType, shortType, true);
        addTransform(charType, intType, false);
        addTransform(charType, longType, false);
        addTransform(charType, floatType, false);
        addTransform(charType, doubleType, false);
        addTransform(charType, objectType, "Character", "valueOf", true, false);
        addTransform(charType, defType, "Character", "valueOf", true, false);
        addTransform(charType, numberType, "Utility", "charToInteger", true, false);
        addTransform(charType, byteobjType, "Utility", "charToByte", true, true);
        addTransform(charType, shortobjType, "Utility", "charToShort", true, true);
        addTransform(charType, charobjType, "Character", "valueOf", true, false);
        addTransform(charType, intobjType, "Utility", "charToInteger", true, false);
        addTransform(charType, longobjType, "Utility", "charToLong", true, false);
        addTransform(charType, floatobjType, "Utility", "charToFloat", true, false);
        addTransform(charType, doubleobjType, "Utility", "charToDouble", true, false);
        addTransform(charType, stringType, "Utility", "charToString", true, true);

        addTransform(intType, byteType, true);
        addTransform(intType, shortType, true);
        addTransform(intType, charType, true);
        addTransform(intType, longType, false);
        addTransform(intType, floatType, false);
        addTransform(intType, doubleType, false);
        addTransform(intType, objectType, "Integer", "valueOf", true, false);
        addTransform(intType, defType, "Integer", "valueOf", true, false);
        addTransform(intType, numberType, "Integer", "valueOf", true, false);
        addTransform(intType, byteobjType, "Utility", "intToByte", true, true);
        addTransform(intType, shortobjType, "Utility", "intToShort", true, true);
        addTransform(intType, charobjType, "Utility", "intToCharacter", true, true);
        addTransform(intType, intobjType, "Integer", "valueOf", true, false);
        addTransform(intType, longobjType, "Utility", "intToLong", true, false);
        addTransform(intType, floatobjType, "Utility", "intToFloat", true, false);
        addTransform(intType, doubleobjType, "Utility", "intToDouble", true, false);

        addTransform(longType, byteType, true);
        addTransform(longType, shortType, true);
        addTransform(longType, charType, true);
        addTransform(longType, intType, false);
        addTransform(longType, floatType, false);
        addTransform(longType, doubleType, false);
        addTransform(longType, objectType, "Long", "valueOf", true, false);
        addTransform(longType, defType, "Long", "valueOf", true, false);
        addTransform(longType, numberType, "Long", "valueOf", true, false);
        addTransform(longType, byteobjType, "Utility", "longToByte", true, true);
        addTransform(longType, shortobjType, "Utility", "longToShort", true, true);
        addTransform(longType, charobjType, "Utility", "longToCharacter", true, true);
        addTransform(longType, intobjType, "Utility", "longToInteger", true, true);
        addTransform(longType, longobjType, "Long", "valueOf", true, false);
        addTransform(longType, floatobjType, "Utility", "longToFloat", true, false);
        addTransform(longType, doubleobjType, "Utility", "longToDouble", true, false);

        addTransform(floatType, byteType, true);
        addTransform(floatType, shortType, true);
        addTransform(floatType, charType, true);
        addTransform(floatType, intType, true);
        addTransform(floatType, longType, false);
        addTransform(floatType, doubleType, false);
        addTransform(floatType, objectType, "Float", "valueOf", true, false);
        addTransform(floatType, defType, "Float", "valueOf", true, false);
        addTransform(floatType, numberType, "Float", "valueOf", true, false);
        addTransform(floatType, byteobjType, "Utility", "floatToByte", true, true);
        addTransform(floatType, shortobjType, "Utility", "floatToShort", true, true);
        addTransform(floatType, charobjType, "Utility", "floatToCharacter", true, true);
        addTransform(floatType, intobjType, "Utility", "floatToInteger", true, true);
        addTransform(floatType, longobjType, "Utility", "floatToLong", true, true);
        addTransform(floatType, floatobjType, "Float", "valueOf", true, false);
        addTransform(floatType, doubleobjType, "Utility", "floatToDouble", true, false);

        addTransform(doubleType, byteType, true);
        addTransform(doubleType, shortType, true);
        addTransform(doubleType, charType, true);
        addTransform(doubleType, intType, true);
        addTransform(doubleType, longType, true);
        addTransform(doubleType, floatType, false);
        addTransform(doubleType, objectType, "Double", "valueOf", true, false);
        addTransform(doubleType, defType, "Double", "valueOf", true, false);
        addTransform(doubleType, numberType, "Double", "valueOf", true, false);
        addTransform(doubleType, byteobjType, "Utility", "doubleToByte", true, true);
        addTransform(doubleType, shortobjType, "Utility", "doubleToShort", true, true);
        addTransform(doubleType, charobjType, "Utility", "doubleToCharacter", true, true);
        addTransform(doubleType, intobjType, "Utility", "doubleToInteger", true, true);
        addTransform(doubleType, longobjType, "Utility", "doubleToLong", true, true);
        addTransform(doubleType, floatobjType, "Utility", "doubleToFloat", true, true);
        addTransform(doubleType, doubleobjType, "Double", "valueOf", true, false);

        addTransform(objectType, booleanType, "Boolean", "booleanValue", false, true);
        addTransform(objectType, byteType, "Number", "byteValue", false, true);
        addTransform(objectType, shortType, "Number", "shortValue", false, true);
        addTransform(objectType, charType, "Character", "charValue", false, true);
        addTransform(objectType, intType, "Number", "intValue", false, true);
        addTransform(objectType, longType, "Number", "longValue", false, true);
        addTransform(objectType, floatType, "Number", "floatValue", false, true);
        addTransform(objectType, doubleType, "Number", "doubleValue", false, true);

        addTransform(defType, booleanType, "Boolean", "booleanValue", false, false);
        addTransform(defType, byteType, "Def", "DefTobyteImplicit", true, false);
        addTransform(defType, shortType, "Def", "DefToshortImplicit", true, false);
        addTransform(defType, charType, "Def", "DefTocharImplicit", true, false);
        addTransform(defType, intType, "Def", "DefTointImplicit", true, false);
        addTransform(defType, longType, "Def", "DefTolongImplicit", true, false);
        addTransform(defType, floatType, "Def", "DefTofloatImplicit", true, false);
        addTransform(defType, doubleType, "Def", "DefTodoubleImplicit", true, false);
        addTransform(defType, byteobjType, "Def", "DefToByteImplicit", true, false);
        addTransform(defType, shortobjType, "Def", "DefToShortImplicit", true, false);
        addTransform(defType, charobjType, "Def", "DefToCharacterImplicit", true, false);
        addTransform(defType, intobjType, "Def", "DefToIntegerImplicit", true, false);
        addTransform(defType, longobjType, "Def", "DefToLongImplicit", true, false);
        addTransform(defType, floatobjType, "Def", "DefToFloatImplicit", true, false);
        addTransform(defType, doubleobjType, "Def", "DefToDoubleImplicit", true, false);
        addTransform(defType, byteType, "Def", "DefTobyteExplicit", true, true);
        addTransform(defType, shortType, "Def", "DefToshortExplicit", true, true);
        addTransform(defType, charType, "Def", "DefTocharExplicit", true, true);
        addTransform(defType, intType, "Def", "DefTointExplicit", true, true);
        addTransform(defType, longType, "Def", "DefTolongExplicit", true, true);
        addTransform(defType, floatType, "Def", "DefTofloatExplicit", true, true);
        addTransform(defType, doubleType, "Def", "DefTodoubleExplicit", true, true);
        addTransform(defType, byteobjType, "Def", "DefToByteExplicit", true, true);
        addTransform(defType, shortobjType, "Def", "DefToShortExplicit", true, true);
        addTransform(defType, charobjType, "Def", "DefToCharacterExplicit", true, true);
        addTransform(defType, intobjType, "Def", "DefToIntegerExplicit", true, true);
        addTransform(defType, longobjType, "Def", "DefToLongExplicit", true, true);
        addTransform(defType, floatobjType, "Def", "DefToFloatExplicit", true, true);
        addTransform(defType, doubleobjType, "Def", "DefToDoubleExplicit", true, true);

        addTransform(numberType, byteType, "Number", "byteValue", false, true);
        addTransform(numberType, shortType, "Number", "shortValue", false, true);
        addTransform(numberType, charType, "Utility", "NumberTochar", true, true);
        addTransform(numberType, intType, "Number", "intValue", false, true);
        addTransform(numberType, longType, "Number", "longValue", false, true);
        addTransform(numberType, floatType, "Number", "floatValue", false, true);
        addTransform(numberType, doubleType, "Number", "doubleValue", false, true);
        addTransform(numberType, booleanobjType, "Utility", "NumberToBoolean", true, true);
        addTransform(numberType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(numberType, shortobjType, "Utility", "NumberToShort", true, true);
        addTransform(numberType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(numberType, intobjType, "Utility", "NumberToInteger", true, true);
        addTransform(numberType, longobjType, "Utility", "NumberToLong", true, true);
        addTransform(numberType, floatobjType, "Utility", "NumberToFloat", true, true);
        addTransform(numberType, doubleobjType, "Utility", "NumberToDouble", true, true);

        addTransform(booleanobjType, booleanType, "Boolean", "booleanValue", false, false);

        addTransform(byteobjType, byteType, "Byte", "byteValue", false, false);
        addTransform(byteobjType, shortType, "Byte", "shortValue", false, false);
        addTransform(byteobjType, charType, "Utility", "ByteTochar", true, false);
        addTransform(byteobjType, intType, "Byte", "intValue", false, false);
        addTransform(byteobjType, longType, "Byte", "longValue", false, false);
        addTransform(byteobjType, floatType, "Byte", "floatValue", false, false);
        addTransform(byteobjType, doubleType, "Byte", "doubleValue", false, false);
        addTransform(byteobjType, shortobjType, "Utility", "NumberToShort", true, false);
        addTransform(byteobjType, charobjType, "Utility", "NumberToCharacter", true, false);
        addTransform(byteobjType, intobjType, "Utility", "NumberToInteger", true, false);
        addTransform(byteobjType, longobjType, "Utility", "NumberToLong", true, false);
        addTransform(byteobjType, floatobjType, "Utility", "NumberToFloat", true, false);
        addTransform(byteobjType, doubleobjType, "Utility", "NumberToDouble", true, false);

        addTransform(shortobjType, byteType, "Short", "byteValue", false, true);
        addTransform(shortobjType, shortType, "Short", "shortValue", false, true);
        addTransform(shortobjType, charType, "Utility", "ShortTochar", true, false);
        addTransform(shortobjType, intType, "Short", "intValue", false, false);
        addTransform(shortobjType, longType, "Short", "longValue", false, false);
        addTransform(shortobjType, floatType, "Short", "floatValue", false, false);
        addTransform(shortobjType, doubleType, "Short", "doubleValue", false, false);
        addTransform(shortobjType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(shortobjType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(shortobjType, intobjType, "Utility", "NumberToInteger", true, false);
        addTransform(shortobjType, longobjType, "Utility", "NumberToLong", true, false);
        addTransform(shortobjType, floatobjType, "Utility", "NumberToFloat", true, false);
        addTransform(shortobjType, doubleobjType, "Utility", "NumberToDouble", true, false);

        addTransform(charobjType, byteType, "Utility", "CharacterTobyte", true, true);
        addTransform(charobjType, shortType, "Utility", "CharacterToshort", true, false);
        addTransform(charobjType, charType, "Character", "charValue", false, true);
        addTransform(charobjType, intType, "Utility", "CharacterToint", true, false);
        addTransform(charobjType, longType, "Utility", "CharacterTolong", true, false);
        addTransform(charobjType, floatType, "Utility", "CharacterTofloat", true, false);
        addTransform(charobjType, doubleType, "Utility", "CharacterTodouble", true, false);
        addTransform(charobjType, byteobjType, "Utility", "CharacterToByte", true, true);
        addTransform(charobjType, shortobjType, "Utility", "CharacterToShort", true, true);
        addTransform(charobjType, intobjType, "Utility", "CharacterToInteger", true, false);
        addTransform(charobjType, longobjType, "Utility", "CharacterToLong", true, false);
        addTransform(charobjType, floatobjType, "Utility", "CharacterToFloat", true, false);
        addTransform(charobjType, doubleobjType, "Utility", "CharacterToDouble", true, false);
        addTransform(charobjType, stringType, "Utility", "CharacterToString", true, true);

        addTransform(intobjType, byteType, "Integer", "byteValue", false, true);
        addTransform(intobjType, shortType, "Integer", "shortValue", false, true);
        addTransform(intobjType, charType, "Utility", "IntegerTochar", true, true);
        addTransform(intobjType, intType, "Integer", "intValue", false, false);
        addTransform(intobjType, longType, "Integer", "longValue", false, false);
        addTransform(intobjType, floatType, "Integer", "floatValue", false, false);
        addTransform(intobjType, doubleType, "Integer", "doubleValue", false, false);
        addTransform(intobjType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(intobjType, shortobjType, "Utility", "NumberToShort", true, true);
        addTransform(intobjType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(intobjType, longobjType, "Utility", "NumberToLong", true, false);
        addTransform(intobjType, floatobjType, "Utility", "NumberToFloat", true, false);
        addTransform(intobjType, doubleobjType, "Utility", "NumberToDouble", true, false);

        addTransform(longobjType, byteType, "Long", "byteValue", false, true);
        addTransform(longobjType, shortType, "Long", "shortValue", false, true);
        addTransform(longobjType, charType, "Utility", "LongTochar", true, true);
        addTransform(longobjType, intType, "Long", "intValue", false, true);
        addTransform(longobjType, longType, "Long", "longValue", false, false);
        addTransform(longobjType, floatType, "Long", "floatValue", false, false);
        addTransform(longobjType, doubleType, "Long", "doubleValue", false, false);
        addTransform(longobjType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(longobjType, shortobjType, "Utility", "NumberToShort", true, true);
        addTransform(longobjType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(longobjType, intobjType, "Utility", "NumberToInteger", true, true);
        addTransform(longobjType, floatobjType, "Utility", "NumberToFloat", true, false);
        addTransform(longobjType, doubleobjType, "Utility", "NumberToDouble", true, false);

        addTransform(floatobjType, byteType, "Float", "byteValue", false, true);
        addTransform(floatobjType, shortType, "Float", "shortValue", false, true);
        addTransform(floatobjType, charType, "Utility", "FloatTochar", true, true);
        addTransform(floatobjType, intType, "Float", "intValue", false, true);
        addTransform(floatobjType, longType, "Float", "longValue", false, true);
        addTransform(floatobjType, floatType, "Float", "floatValue", false, false);
        addTransform(floatobjType, doubleType, "Float", "doubleValue", false, false);
        addTransform(floatobjType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(floatobjType, shortobjType, "Utility", "NumberToShort", true, true);
        addTransform(floatobjType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(floatobjType, intobjType, "Utility", "NumberToInteger", true, true);
        addTransform(floatobjType, longobjType, "Utility", "NumberToLong", true, true);
        addTransform(floatobjType, doubleobjType, "Utility", "NumberToDouble", true, false);

        addTransform(doubleobjType, byteType, "Double", "byteValue", false, true);
        addTransform(doubleobjType, shortType, "Double", "shortValue", false, true);
        addTransform(doubleobjType, charType, "Utility", "DoubleTochar", true, true);
        addTransform(doubleobjType, intType, "Double", "intValue", false, true);
        addTransform(doubleobjType, longType, "Double", "longValue", false, true);
        addTransform(doubleobjType, floatType, "Double", "floatValue", false, true);
        addTransform(doubleobjType, doubleType, "Double", "doubleValue", false, false);
        addTransform(doubleobjType, byteobjType, "Utility", "NumberToByte", true, true);
        addTransform(doubleobjType, shortobjType, "Utility", "NumberToShort", true, true);
        addTransform(doubleobjType, charobjType, "Utility", "NumberToCharacter", true, true);
        addTransform(doubleobjType, intobjType, "Utility", "NumberToInteger", true, true);
        addTransform(doubleobjType, longobjType, "Utility", "NumberToLong", true, true);
        addTransform(doubleobjType, floatobjType, "Utility", "NumberToFloat", true, true);

        addTransform(stringType, charType, "Utility", "StringTochar", true, true);
        addTransform(stringType, charobjType, "Utility", "StringToCharacter", true, true);
    }

    private void addRuntimeClasses() {
        addRuntimeClass(booleanType.struct);
        addRuntimeClass(byteType.struct);
        addRuntimeClass(shortType.struct);
        addRuntimeClass(charType.struct);
        addRuntimeClass(intType.struct);
        addRuntimeClass(longType.struct);
        addRuntimeClass(floatType.struct);
        addRuntimeClass(doubleType.struct);

        addRuntimeClass(booleanobjType.struct);
        addRuntimeClass(byteobjType.struct);
        addRuntimeClass(shortobjType.struct);
        addRuntimeClass(charobjType.struct);
        addRuntimeClass(intobjType.struct);
        addRuntimeClass(longobjType.struct);
        addRuntimeClass(floatobjType.struct);
        addRuntimeClass(doubleobjType.struct);

        addRuntimeClass(objectType.struct);
        addRuntimeClass(numberType.struct);
        addRuntimeClass(charseqType.struct);
        addRuntimeClass(stringType.struct);

        addRuntimeClass(oitrType.struct);
        addRuntimeClass(ocollectionType.struct);
        addRuntimeClass(olistType.struct);
        addRuntimeClass(oarraylistType.struct);
        addRuntimeClass(osetType.struct);
        addRuntimeClass(ohashsetType.struct);
        addRuntimeClass(oomapType.struct);
        addRuntimeClass(oohashmapType.struct);

        addRuntimeClass(exceptionType.struct);

        addRuntimeClass(geoPointType.struct);
        addRuntimeClass(stringsType.struct);
        addRuntimeClass(longsType.struct);
        addRuntimeClass(doublesType.struct);
        addRuntimeClass(geoPointsType.struct);

        addRuntimeClass(featureTestType.struct);
    }

    private final void addStruct(final String name, final Class<?> clazz) {
        if (!name.matches("^[_a-zA-Z][<>,_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid struct name [" + name + "].");
        }

        if (structsMap.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate struct name [" + name + "].");
        }

        final Struct struct = new Struct(name, clazz, org.objectweb.asm.Type.getType(clazz));

        structsMap.put(name, struct);
    }

    private final void addConstructor(final String struct, final String name, final Type[] args, final Type[] genargs) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException(
                "Owner struct [" + struct + "] not defined for constructor [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException(
                "Invalid constructor name [" + name + "] with the struct [" + owner.name + "].");
        }

        MethodKey methodKey = new MethodKey(name, args.length);

        if (owner.constructors.containsKey(methodKey)) {
            throw new IllegalArgumentException(
                "Duplicate constructor [" + methodKey + "] found within the struct [" + owner.name + "].");
        }

        if (owner.staticMethods.containsKey(methodKey)) {
            throw new IllegalArgumentException("Constructors and static methods may not have the same signature" +
                " [" + methodKey + "] within the same struct [" + owner.name + "].");
        }

        if (owner.methods.containsKey(methodKey)) {
            throw new IllegalArgumentException("Constructors and methods may not have the same signature" +
                " [" + methodKey + "] within the same struct [" + owner.name + "].");
        }

        final Class<?>[] classes = new Class<?>[args.length];

        for (int count = 0; count < classes.length; ++count) {
            if (genargs != null) {
                if (!args[count].clazz.isAssignableFrom(genargs[count].clazz)) {
                    throw new ClassCastException("Generic argument [" + genargs[count].name + "]" +
                        " is not a sub class of [" + args[count].name + "] in the constructor" +
                        " [" + name + " ] from the struct [" + owner.name + "].");
                }
            }

            classes[count] = args[count].clazz;
        }

        final java.lang.reflect.Constructor<?> reflect;

        try {
            reflect = owner.clazz.getConstructor(classes);
        } catch (final NoSuchMethodException exception) {
            throw new IllegalArgumentException("Constructor [" + name + "] not found for class" +
                " [" + owner.clazz.getName() + "] with arguments " + Arrays.toString(classes) + ".");
        }

        final org.objectweb.asm.commons.Method asm = org.objectweb.asm.commons.Method.getMethod(reflect);
        final Constructor constructor =
            new Constructor(name, owner, Arrays.asList(genargs != null ? genargs : args), asm, reflect);

        owner.constructors.put(methodKey, constructor);
    }

    private final void addMethod(final String struct, final String name, final String alias, final boolean statik,
                                final Type rtn, final Type[] args, final Type genrtn, final Type[] genargs) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined" +
                " for " + (statik ? "function" : "method") + " [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid " + (statik ? "static method" : "method") +
                " name [" + name + "] with the struct [" + owner.name + "].");
        }

        MethodKey methodKey = new MethodKey(name, args.length);

        if (owner.constructors.containsKey(methodKey)) {
            throw new IllegalArgumentException("Constructors and " + (statik ? "static methods" : "methods") +
                " may not have the same signature [" + methodKey + "] within the same struct" +
                " [" + owner.name + "].");
        }

        if (owner.staticMethods.containsKey(methodKey)) {
            if (statik) {
                throw new IllegalArgumentException(
                    "Duplicate static method signature [" + methodKey + "] found within the struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Static methods and methods may not have the same signature" +
                    " [" + methodKey + "] within the same struct [" + owner.name + "].");
            }
        }

        if (owner.methods.containsKey(methodKey)) {
            if (statik) {
                throw new IllegalArgumentException("Static methods and methods may not have the same signature" +
                    " [" + methodKey + "] within the same struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Duplicate method signature [" + methodKey + "]" +
                    " found within the struct [" + owner.name + "].");
            }
        }

        if (genrtn != null) {
            if (!rtn.clazz.isAssignableFrom(genrtn.clazz)) {
                throw new ClassCastException("Generic return [" + genrtn.clazz.getCanonicalName() + "]" +
                    " is not a sub class of [" + rtn.clazz.getCanonicalName() + "] in the method" +
                    " [" + name + " ] from the struct [" + owner.name + "].");
            }
        }

        if (genargs != null && genargs.length != args.length) {
            throw new IllegalArgumentException("Generic arguments arity [" +  genargs.length + "] is not the same as " +
                (statik ? "function" : "method") + " [" + name + "] arguments arity" +
                " [" + args.length + "] within the struct [" + owner.name + "].");
        }

        final Class<?>[] classes = new Class<?>[args.length];

        for (int count = 0; count < classes.length; ++count) {
            if (genargs != null) {
                if (!args[count].clazz.isAssignableFrom(genargs[count].clazz)) {
                    throw new ClassCastException("Generic argument [" + genargs[count].name + "] is not a sub class" +
                        " of [" + args[count].name + "] in the " + (statik ? "function" : "method") +
                        " [" + name + " ] from the struct [" + owner.name + "].");
                }
            }

            classes[count] = args[count].clazz;
        }

        final java.lang.reflect.Method reflect;

        try {
            reflect = owner.clazz.getMethod(alias == null ? name : alias, classes);
        } catch (final NoSuchMethodException exception) {
            throw new IllegalArgumentException((statik ? "Function" : "Method") +
                " [" + (alias == null ? name : alias) + "] not found for class [" + owner.clazz.getName() + "]" +
                " with arguments " + Arrays.toString(classes) + ".");
        }

        if (!reflect.getReturnType().equals(rtn.clazz)) {
            throw new IllegalArgumentException("Specified return type class [" + rtn.clazz + "]" +
                " does not match the found return type class [" + reflect.getReturnType() + "] for the " +
                (statik ? "function" : "method") + " [" + name + "]" +
                " within the struct [" + owner.name + "].");
        }

        final org.objectweb.asm.commons.Method asm = org.objectweb.asm.commons.Method.getMethod(reflect);

        MethodHandle handle;

        try {
            handle = MethodHandles.publicLookup().in(owner.clazz).unreflect(reflect);
        } catch (final IllegalAccessException exception) {
            throw new IllegalArgumentException("Method [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "]" +
                " with arguments " + Arrays.toString(classes) + ".");
        }

        final Method method = new Method(name, owner, genrtn != null ? genrtn : rtn,
            Arrays.asList(genargs != null ? genargs : args), asm, reflect, handle);
        final int modifiers = reflect.getModifiers();

        if (statik) {
            if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Function [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to a static Java method.");
            }

            owner.staticMethods.put(methodKey, method);
        } else {
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Method [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to a non-static Java method.");
            }

            owner.methods.put(methodKey, method);
        }
    }

    private final void addField(final String struct, final String name, final String alias,
                               final boolean statik, final Type type, final Type generic) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for " +
                (statik ? "static" : "member") + " [" + name + "].");
        }

        if (!name.matches("^[_a-zA-Z][_a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid " + (statik ? "static" : "member") +
                " name [" + name + "] with the struct [" + owner.name + "].");
        }

        if (owner.staticMembers.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException("Duplicate static name [" + name + "]" +
                    " found within the struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Statics and members may not have the same name " +
                    "[" + name + "] within the same struct [" + owner.name + "].");
            }
        }

        if (owner.members.containsKey(name)) {
            if (statik) {
                throw new IllegalArgumentException("Statics and members may not have the same name " +
                    "[" + name + "] within the same struct [" + owner.name + "].");
            } else {
                throw new IllegalArgumentException("Duplicate member name [" + name + "]" +
                    " found within the struct [" + owner.name + "].");
            }
        }

        if (generic != null) {
            if (!type.clazz.isAssignableFrom(generic.clazz)) {
                throw new ClassCastException("Generic type [" + generic.clazz.getCanonicalName() + "]" +
                    " is not a sub class of [" + type.clazz.getCanonicalName() + "] for the field" +
                    " [" + name + " ] from the struct [" + owner.name + "].");
            }
        }

        java.lang.reflect.Field reflect;

        try {
            reflect = owner.clazz.getField(alias == null ? name : alias);
        } catch (final NoSuchFieldException exception) {
            throw new IllegalArgumentException("Field [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "].");
        }

        MethodHandle getter = null;
        MethodHandle setter = null;

        try {
            if (!statik) {
                getter = MethodHandles.publicLookup().unreflectGetter(reflect);
                setter = MethodHandles.publicLookup().unreflectSetter(reflect);
            }
        } catch (final IllegalAccessException exception) {
            throw new IllegalArgumentException("Getter/Setter [" + (alias == null ? name : alias) + "]" +
                " not found for class [" + owner.clazz.getName() + "].");
        }

        final Field field = new Field(name, owner, generic == null ? type : generic, type, reflect, getter, setter);
        final int modifiers = reflect.getModifiers();

        if (statik) {
            if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException();
            }

            if (!java.lang.reflect.Modifier.isFinal(modifiers)) {
                throw new IllegalArgumentException("Static [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to static Java field.");
            }

            owner.staticMembers.put(alias == null ? name : alias, field);
        } else {
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Member [" + name + "]" +
                    " within the struct [" + owner.name + "] is not linked to non-static Java field.");
            }

            owner.members.put(alias == null ? name : alias, field);
        }
    }

    private final void copyStruct(final String struct, final String... children) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for copy.");
        }

        for (int count = 0; count < children.length; ++count) {
            final Struct child = structsMap.get(children[count]);

            if (struct == null) {
                throw new IllegalArgumentException("Child struct [" + children[count] + "]" +
                    " not defined for copy to owner struct [" + owner.name + "].");
            }

            if (!child.clazz.isAssignableFrom(owner.clazz)) {
                throw new ClassCastException("Child struct [" + child.name + "]" +
                    " is not a super type of owner struct [" + owner.name + "] in copy.");
            }

            final boolean object = child.clazz.equals(Object.class) &&
                java.lang.reflect.Modifier.isInterface(owner.clazz.getModifiers());

            for (Map.Entry<MethodKey,Method> kvPair : child.methods.entrySet()) {
                MethodKey methodKey = kvPair.getKey();
                Method method = kvPair.getValue();
                if (owner.methods.get(methodKey) == null) {
                    final Class<?> clazz = object ? Object.class : owner.clazz;

                    java.lang.reflect.Method reflect;
                    MethodHandle handle;

                    try {
                        reflect = clazz.getMethod(method.method.getName(), method.reflect.getParameterTypes());
                    } catch (final NoSuchMethodException exception) {
                        throw new IllegalArgumentException("Method [" + method.method.getName() + "] not found for" +
                            " class [" + owner.clazz.getName() + "] with arguments " +
                            Arrays.toString(method.reflect.getParameterTypes()) + ".");
                    }

                    try {
                        handle = MethodHandles.publicLookup().in(owner.clazz).unreflect(reflect);
                    } catch (final IllegalAccessException exception) {
                        throw new IllegalArgumentException("Method [" + method.method.getName() + "] not found for" +
                            " class [" + owner.clazz.getName() + "] with arguments " +
                            Arrays.toString(method.reflect.getParameterTypes()) + ".");
                    }

                    owner.methods.put(methodKey,
                        new Method(method.name, owner, method.rtn, method.arguments, method.method, reflect, handle));
                }
            }

            for (final Field field : child.members.values()) {
                if (owner.members.get(field.name) == null) {
                    java.lang.reflect.Field reflect;
                    MethodHandle getter;
                    MethodHandle setter;

                    try {
                        reflect = owner.clazz.getField(field.reflect.getName());
                    } catch (final NoSuchFieldException exception) {
                        throw new IllegalArgumentException("Field [" + field.reflect.getName() + "]" +
                            " not found for class [" + owner.clazz.getName() + "].");
                    }

                    try {
                        getter = MethodHandles.publicLookup().unreflectGetter(reflect);
                        setter = MethodHandles.publicLookup().unreflectSetter(reflect);
                    } catch (final IllegalAccessException exception) {
                        throw new IllegalArgumentException("Getter/Setter [" + field.name + "]" +
                            " not found for class [" + owner.clazz.getName() + "].");
                    }

                    owner.members.put(field.name,
                        new Field(field.name, owner, field.type, field.generic, reflect, getter, setter));
                }
            }
        }
    }

    private final void addTransform(final Type from, final Type to, final boolean explicit) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("Transform cannot" +
                " have cast type from [" + from.name + "] be the same as cast type to [" + to.name + "].");
        }

        if (!from.sort.primitive || !to.sort.primitive) {
            throw new IllegalArgumentException("Only transforms between two primitives may be a simple cast, but" +
                "found [" + from.name + "] and [" + to.name + "].");
        }

        final Cast cast = new Cast(from, to, explicit);

        if (transformsMap.containsKey(cast)) {
            throw new IllegalArgumentException("Transform with " +
                " cast type from [" + from.name + "] to cast type to [" + to.name + "] already defined.");
        }

        transformsMap.put(cast, cast);
    }

    private final void addTransform(final Type from, final Type to, final String struct,
                                   final String name, final boolean statik, final boolean explicit) {
        final Struct owner = structsMap.get(struct);

        if (owner == null) {
            throw new IllegalArgumentException("Owner struct [" + struct + "] not defined for" +
                " transform with cast type from [" + from.name + "] and cast type to [" + to.name + "].");
        }

        if (from.equals(to)) {
            throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "] cannot" +
                " have cast type from [" + from.name + "] be the same as cast type to [" + to.name + "].");
        }

        final Cast cast = new Cast(from, to, explicit);

        if (transformsMap.containsKey(cast)) {
            throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                " and cast type from [" + from.name + "] to cast type to [" + to.name + "] already defined.");
        }

        final Cast transform;

        final Method method;
        Type upcast = null;
        Type downcast = null;

        // transforms are implicitly arity of 0, unless a static method where its 1 (receiver passed)
        final MethodKey methodKey = new MethodKey(name, statik ? 1 : 0);

        if (statik) {
            method = owner.staticMethods.get(methodKey);

            if (method == null) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using a function [" + name + "] that is not defined.");
            }

            if (method.arguments.size() != 1) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using function [" + name + "] does not have a single type argument.");
            }

            Type argument = method.arguments.get(0);

            if (!argument.clazz.isAssignableFrom(from.clazz)) {
                if (from.clazz.isAssignableFrom(argument.clazz)) {
                    upcast = argument;
                } else {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " function [" + name + "] cannot cast from type to the function input argument type.");
                }
            }

            final Type rtn = method.rtn;

            if (!to.clazz.isAssignableFrom(rtn.clazz)) {
                if (rtn.clazz.isAssignableFrom(to.clazz)) {
                    downcast = to;
                } else {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " function [" + name + "] cannot cast to type to the function return argument type.");
                }
            }
        } else {
            method = owner.methods.get(methodKey);

            if (method == null) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using a method [" + name + "] that is not defined.");
            }

            if (!method.arguments.isEmpty()) {
                throw new IllegalArgumentException("Transform with owner struct [" + owner.name + "]" +
                    " and cast type from [" + from.name + "] to cast type to [" + to.name +
                    "] using method [" + name + "] does not have a single type argument.");
            }

            if (!owner.clazz.isAssignableFrom(from.clazz)) {
                if (from.clazz.isAssignableFrom(owner.clazz)) {
                    upcast = getType(owner.name);
                } else {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "] using" +
                        " method [" + name + "] cannot cast from type to the method input argument type.");
                }
            }

            final Type rtn = method.rtn;

            if (!to.clazz.isAssignableFrom(rtn.clazz)) {
                if (rtn.clazz.isAssignableFrom(to.clazz)) {
                    downcast = to;
                } else {
                    throw new ClassCastException("Transform with owner struct [" + owner.name + "]" +
                        " and cast type from [" + from.name + "] to cast type to [" + to.name + "]" +
                        " using method [" + name + "] cannot cast to type to the method return argument type.");
                }
            }
        }

        transform = new Transform(cast, method, upcast, downcast);
        transformsMap.put(cast, transform);
    }

    /**
     * Precomputes a more efficient structure for dynamic method/field access.
     */
    private void addRuntimeClass(final Struct struct) {
        final Map<MethodKey, Method> methods = struct.methods;
        final Map<String, MethodHandle> getters = new HashMap<>();
        final Map<String, MethodHandle> setters = new HashMap<>();

        // add all members
        for (final Map.Entry<String, Field> member : struct.members.entrySet()) {
            getters.put(member.getKey(), member.getValue().getter);
            setters.put(member.getKey(), member.getValue().setter);
        }

        // add all getters/setters
        for (final Map.Entry<MethodKey, Method> method : methods.entrySet()) {
            final String name = method.getKey().name;
            final Method m = method.getValue();

            if (m.arguments.size() == 0 &&
                name.startsWith("get") &&
                name.length() > 3 &&
                Character.isUpperCase(name.charAt(3))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(3)));
                newName.append(name.substring(4));
                getters.putIfAbsent(newName.toString(), m.handle);
            } else if (m.arguments.size() == 0 &&
                name.startsWith("is") &&
                name.length() > 2 &&
                Character.isUpperCase(name.charAt(2))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(2)));
                newName.append(name.substring(3));
                getters.putIfAbsent(newName.toString(), m.handle);
            }

            if (m.arguments.size() == 1 &&
                name.startsWith("set") &&
                name.length() > 3 &&
                Character.isUpperCase(name.charAt(3))) {
                final StringBuilder newName = new StringBuilder();
                newName.append(Character.toLowerCase(name.charAt(3)));
                newName.append(name.substring(4));
                setters.putIfAbsent(newName.toString(), m.handle);
            }
        }

        runtimeMap.put(struct.clazz, new RuntimeClass(methods, getters, setters));
    }

    public final Type getType(final String name) {
        final int dimensions = getDimensions(name);
        final String structstr = dimensions == 0 ? name : name.substring(0, name.indexOf('['));
        final Struct struct = structsMap.get(structstr);

        if (struct == null) {
            throw new IllegalArgumentException("The struct with name [" + name + "] has not been defined.");
        }

        return getType(struct, dimensions);
    }

    public final Type getType(final Struct struct, final int dimensions) {
        String name = struct.name;
        org.objectweb.asm.Type type = struct.type;
        Class<?> clazz = struct.clazz;
        Sort sort;

        if (dimensions > 0) {
            final StringBuilder builder = new StringBuilder(name);
            final char[] brackets = new char[dimensions];

            for (int count = 0; count < dimensions; ++count) {
                builder.append("[]");
                brackets[count] = '[';
            }

            final String descriptor = new String(brackets) + struct.type.getDescriptor();

            name = builder.toString();
            type = org.objectweb.asm.Type.getType(descriptor);

            try {
                clazz = Class.forName(type.getInternalName().replace('/', '.'));
            } catch (final ClassNotFoundException exception) {
                throw new IllegalArgumentException("The class [" + type.getInternalName() + "]" +
                    " could not be found to create type [" + name + "].");
            }

            sort = Sort.ARRAY;
        } else if ("def".equals(struct.name)) {
            sort = Sort.DEF;
        } else {
            sort = Sort.OBJECT;

            for (final Sort value : Sort.values()) {
                if (value.clazz == null) {
                    continue;
                }

                if (value.clazz.equals(struct.clazz)) {
                    sort = value;

                    break;
                }
            }
        }

        return new Type(name, dimensions, struct, clazz, type, sort);
    }

    private int getDimensions(final String name) {
        int dimensions = 0;
        int index = name.indexOf('[');

        if (index != -1) {
            final int length = name.length();

            while (index < length) {
                if (name.charAt(index) == '[' && ++index < length && name.charAt(index++) == ']') {
                    ++dimensions;
                } else {
                    throw new IllegalArgumentException("Invalid array braces in canonical name [" + name + "].");
                }
            }
        }

        return dimensions;
    }
}
