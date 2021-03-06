package org.optaplanner.core.impl.domain.solution.cloner.gizmo;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.optaplanner.core.api.domain.solution.cloner.SolutionCloner;
import org.optaplanner.core.impl.domain.common.accessor.gizmo.GizmoMemberDescriptor;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;

import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public class GizmoSolutionClonerImplementor {
    /**
     * The Gizmo generated bytecode. Used by
     * gizmoClassLoader when not run in Quarkus
     * in order to create an instance of the Member
     * Accessor
     */
    private static final Map<String, byte[]> classNameToBytecode = new HashMap<>();

    // Sorts a list of classes into most specific (subclasses) to least specific (classes),
    // then by name.
    private static final Comparator<Class<?>> instanceOfComparator =
            Comparator.<Class<?>, Class<?>> comparing(Function.identity(), (a, b) -> {
                if (a.isAssignableFrom(b)) {
                    return -1;
                } else if (b.isAssignableFrom(a)) {
                    return 1;
                } else {
                    return 0;
                }
            }).thenComparing(Class::getName);

    private static final MethodDescriptor EQUALS_METHOD = MethodDescriptor.ofMethod(Object.class, "equals", boolean.class,
            Object.class);
    private static final MethodDescriptor GET_METHOD = MethodDescriptor.ofMethod(Map.class, "get", Object.class,
            Object.class);
    private static final MethodDescriptor PUT_METHOD = MethodDescriptor.ofMethod(Map.class, "put", Object.class,
            Object.class, Object.class);
    private static final boolean DEBUG = true;

    /**
     * A custom classloader that looks for the class in
     * classNameToBytecode
     */
    private static ClassLoader gizmoClassLoader = new ClassLoader() {
        // getName() is an abstract method in Java 11 but not in Java 8
        public String getName() {
            return "OptaPlanner Gizmo SolutionCloner ClassLoader";
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            if (classNameToBytecode.containsKey(name)) {
                // Gizmo generated class
                byte[] byteCode = classNameToBytecode.get(name);
                return defineClass(name, byteCode, 0, byteCode.length);
            } else {
                // Not a Gizmo generated class; load from context class loader
                return Thread.currentThread().getContextClassLoader().loadClass(name);
            }
        }
    };

    /**
     * Generates the constructor and implementations of SolutionCloner
     * methods for the given SolutionDescriptor using the given ClassCreator
     *
     * @param classCreator ClassCreator to write output to
     * @param solutionDescriptor SolutionDescriptor to generate MemberAccessor methods implementation for
     */
    public static void defineClonerFor(ClassCreator classCreator, SolutionDescriptor<?> solutionDescriptor,
            List<Class<?>> solutionClassList,
            Map<Class<?>, GizmoSolutionOrEntityDescriptor> memoizedSolutionOrEntityDescriptorMap) {
        createConstructor(classCreator);
        createCloneSolution(classCreator, solutionDescriptor);
        createCloneSolutionRun(classCreator, solutionDescriptor, solutionClassList, memoizedSolutionOrEntityDescriptorMap);

        for (Class<?> entityClass : solutionDescriptor.getEntityClassSet()) {
            createEntityHelperMethod(classCreator, entityClass, solutionDescriptor, memoizedSolutionOrEntityDescriptorMap);
        }
    }

    public static <T> SolutionCloner<T> createClonerFor(SolutionDescriptor<T> solutionDescriptor) {
        String className = GizmoSolutionClonerFactory.getGeneratedClassName(solutionDescriptor);
        if (classNameToBytecode.containsKey(className)) {
            return createInstance(className);
        }
        final byte[][] classBytecodeHolder = new byte[1][];
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder[0] = byteCode;

            if (DEBUG) {
                Path debugRoot = Paths.get("target/optaplanner-generated-classes");
                Path rest = Paths.get(path + ".class");
                Path destination = debugRoot.resolve(rest);

                try {
                    Files.createDirectories(destination.getParent());
                    Files.write(destination, byteCode);
                } catch (IOException e) {
                    throw new IllegalStateException("Fail to write debug class file " + destination + ".", e);
                }
            }
        };
        ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(SolutionCloner.class)
                .superClass(Object.class)
                .classOutput(classOutput)
                .setFinal(true)
                .build();

        defineClonerFor(classCreator, solutionDescriptor,
                Arrays.asList(solutionDescriptor.getSolutionClass()),
                new HashMap<>());

        classCreator.close();
        byte[] classBytecode = classBytecodeHolder[0];

        classNameToBytecode.put(className, classBytecode);
        return createInstance(className);
    }

    private static <T> SolutionCloner<T> createInstance(String className) {
        try {
            return (SolutionCloner<T>) gizmoClassLoader.loadClass(className)
                    .getConstructor().newInstance();
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException | ClassNotFoundException
                | NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void createConstructor(ClassCreator classCreator) {
        MethodCreator methodCreator =
                classCreator.getMethodCreator(MethodDescriptor.ofConstructor(classCreator.getClassName()));

        ResultHandle thisObj = methodCreator.getThis();

        // Invoke Object's constructor
        methodCreator.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), thisObj);

        // Return this (it a constructor)
        methodCreator.returnValue(thisObj);
    }

    private static void createCloneSolution(ClassCreator classCreator, SolutionDescriptor<?> solutionDescriptor) {
        Class<?> solutionClass = solutionDescriptor.getSolutionClass();
        MethodCreator methodCreator =
                classCreator.getMethodCreator(MethodDescriptor.ofMethod(SolutionCloner.class,
                        "cloneSolution",
                        Object.class,
                        Object.class));

        ResultHandle thisObj = methodCreator.getMethodParam(0);

        ResultHandle clone = methodCreator.invokeStaticMethod(
                MethodDescriptor.ofMethod(
                        GizmoSolutionClonerFactory.getGeneratedClassName(solutionDescriptor),
                        "cloneSolutionRun", solutionClass, solutionClass, Map.class),
                thisObj,
                methodCreator.newInstance(MethodDescriptor.ofConstructor(IdentityHashMap.class)));
        methodCreator.returnValue(clone);
    }

    private static void createCloneSolutionRun(ClassCreator classCreator, SolutionDescriptor solutionDescriptor,
            List<Class<?>> solutionClassList,
            Map<Class<?>, GizmoSolutionOrEntityDescriptor> memoizedSolutionOrEntityDescriptorMap) {
        Class<?> solutionClass = solutionDescriptor.getSolutionClass();
        MethodCreator methodCreator =
                classCreator.getMethodCreator("cloneSolutionRun", solutionClass, solutionClass, Map.class);
        methodCreator.setModifiers(Modifier.STATIC | Modifier.PRIVATE);

        ResultHandle thisObj = methodCreator.getMethodParam(0);
        BranchResult solutionNullBranchResult = methodCreator.ifNull(thisObj);
        BytecodeCreator solutionIsNullBranch = solutionNullBranchResult.trueBranch();
        solutionIsNullBranch.returnValue(thisObj); // thisObj is null

        BytecodeCreator solutionIsNotNullBranch = solutionNullBranchResult.falseBranch();

        ResultHandle createdCloneMap = methodCreator.getMethodParam(1);

        ResultHandle maybeClone = solutionIsNotNullBranch.invokeInterfaceMethod(
                GET_METHOD, createdCloneMap, thisObj);
        BranchResult hasCloneBranchResult = solutionIsNotNullBranch.ifNotNull(maybeClone);
        BytecodeCreator hasCloneBranch = hasCloneBranchResult.trueBranch();
        hasCloneBranch.returnValue(maybeClone);

        BytecodeCreator noCloneBranch = hasCloneBranchResult.falseBranch();
        List<Class<?>> sortedSolutionClassList = new ArrayList<>(solutionClassList);
        sortedSolutionClassList.sort(instanceOfComparator);

        BytecodeCreator currentBranch = noCloneBranch;
        ResultHandle thisObjClass =
                currentBranch.invokeVirtualMethod(MethodDescriptor.ofMethod(Object.class, "getClass", Class.class), thisObj);
        for (Class<?> solutionSubclass : sortedSolutionClassList) {
            ResultHandle solutionSubclassResultHandle = currentBranch.loadClass(solutionSubclass);
            ResultHandle isSubclass =
                    currentBranch.invokeVirtualMethod(EQUALS_METHOD, solutionSubclassResultHandle, thisObjClass);
            BranchResult isSubclassBranchResult = currentBranch.ifTrue(isSubclass);

            BytecodeCreator isSubclassBranch = isSubclassBranchResult.trueBranch();

            GizmoSolutionOrEntityDescriptor solutionSubclassDescriptor =
                    memoizedSolutionOrEntityDescriptorMap.computeIfAbsent(solutionSubclass,
                            (key) -> new GizmoSolutionOrEntityDescriptor(solutionDescriptor, solutionSubclass));
            ResultHandle clone = isSubclassBranch.newInstance(MethodDescriptor.ofConstructor(solutionSubclass));

            isSubclassBranch.invokeInterfaceMethod(
                    MethodDescriptor.ofMethod(Map.class, "put", Object.class, Object.class, Object.class),
                    createdCloneMap, thisObj, clone);

            for (GizmoMemberDescriptor shallowlyClonedField : solutionSubclassDescriptor.getShallowClonedMemberDescriptors()) {
                writeShallowCloneInstructions(solutionSubclassDescriptor, isSubclassBranch, shallowlyClonedField, thisObj,
                        clone, createdCloneMap);
            }

            for (Field deeplyClonedField : solutionSubclassDescriptor.getDeepClonedFields()) {
                GizmoMemberDescriptor gizmoMemberDescriptor =
                        solutionSubclassDescriptor.getMemberDescriptorForField(deeplyClonedField);

                ResultHandle fieldValue = gizmoMemberDescriptor.readMemberValue(isSubclassBranch, thisObj);
                AssignableResultHandle cloneValue = isSubclassBranch.createVariable(deeplyClonedField.getType());
                writeDeepCloneInstructions(isSubclassBranch, solutionSubclassDescriptor,
                        deeplyClonedField.getType(), gizmoMemberDescriptor.getType(), fieldValue, cloneValue, createdCloneMap);

                if (!gizmoMemberDescriptor.writeMemberValue(isSubclassBranch, clone, cloneValue)) {
                    throw new IllegalStateException("The member (" + gizmoMemberDescriptor.getName() + ") of class (" +
                            gizmoMemberDescriptor.getDeclaringClassName() +
                            ") does not have a setter.");
                }
            }
            isSubclassBranch.returnValue(clone);

            currentBranch = isSubclassBranchResult.falseBranch();
        }
        ResultHandle errorBuilder = currentBranch.newInstance(MethodDescriptor.ofConstructor(StringBuilder.class, String.class),
                currentBranch.load("Failed to create clone: encountered ("));
        final MethodDescriptor APPEND =
                MethodDescriptor.ofMethod(StringBuilder.class, "append", StringBuilder.class, Object.class);

        currentBranch.invokeVirtualMethod(APPEND, errorBuilder, thisObjClass);
        currentBranch.invokeVirtualMethod(APPEND, errorBuilder, currentBranch.load(") which is not a known subclass of " +
                "the solution class (" + solutionDescriptor.getSolutionClass() + "). The known subclasses are " +
                solutionClassList.stream().map(Class::getName).collect(Collectors.joining(", ", "[", "]")) + "." +
                "\nMaybe use DomainAccessType.REFLECTION?"));
        ResultHandle errorMsg = currentBranch
                .invokeVirtualMethod(MethodDescriptor.ofMethod(Object.class, "toString", String.class), errorBuilder);
        ResultHandle error = currentBranch
                .newInstance(MethodDescriptor.ofConstructor(IllegalArgumentException.class, String.class), errorMsg);
        currentBranch.throwException(error);
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // If getter a field
     * clone.member = original.member
     * // If getter a method (i.e. Quarkus)
     * clone.setMember(original.getMember());
     * </pre>
     * 
     * @param methodCreator
     * @param shallowlyClonedField
     * @param thisObj
     * @param clone
     */
    private static void writeShallowCloneInstructions(GizmoSolutionOrEntityDescriptor solutionInfo,
            BytecodeCreator methodCreator, GizmoMemberDescriptor shallowlyClonedField,
            ResultHandle thisObj, ResultHandle clone, ResultHandle createdCloneMap) {
        try {
            boolean isArray = shallowlyClonedField.getTypeName().endsWith("[]");
            Class<?> type = null;
            if (shallowlyClonedField.getType() instanceof Class) {
                type = (Class<?>) shallowlyClonedField.getType();
            }

            List<Class<?>> entitySubclasses = Collections.emptyList();
            if (type == null && !isArray) {
                type = Class.forName(shallowlyClonedField.getTypeName().replace('/', '.'), false,
                        Thread.currentThread().getContextClassLoader());
            }

            if (type != null && !isArray) {
                entitySubclasses = solutionInfo.getSolutionDescriptor().getEntityClassSet().stream()
                        .filter(type::isAssignableFrom).sorted(instanceOfComparator).collect(Collectors.toList());
            }

            ResultHandle fieldValue = shallowlyClonedField.readMemberValue(methodCreator, thisObj);
            if (!entitySubclasses.isEmpty()) {
                AssignableResultHandle cloneResultHolder = methodCreator.createVariable(type);
                writeDeepCloneEntityInstructions(methodCreator, solutionInfo, type,
                        fieldValue, cloneResultHolder, createdCloneMap);
                fieldValue = cloneResultHolder;
            }
            if (!shallowlyClonedField.writeMemberValue(methodCreator, clone, fieldValue)) {
                throw new IllegalStateException("Field (" + shallowlyClonedField.getName() + ") of class (" +
                        shallowlyClonedField.getDeclaringClassName() +
                        ") does not have a setter.");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Error creating Gizmo Solution Cloner", e);
        }
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // For a Collection
     * Collection original = field;
     * Collection clone = new ActualCollectionType();
     * Iterator iterator = original.iterator();
     * while (iterator.hasNext()) {
     *     Object nextClone = (result from recursion on iterator.next());
     *     clone.add(nextClone);
     * }
     *
     * // For a Map
     * Map original = field;
     * Map clone = new ActualMapType();
     * Iterator iterator = original.entrySet().iterator();
     * while (iterator.hasNext()) {
     *      Entry next = iterator.next();
     *      nextClone = (result from recursion on next.getValue());
     *      clone.put(next.getKey(), nextClone);
     * }
     *
     * // For an array
     * Object[] original = field;
     * Object[] clone = new Object[original.length];
     *
     * for (int i = 0; i < original.length; i++) {
     *     clone[i] = (result from recursion on original[i]);
     * }
     *
     * // For an entity
     * if (original instanceof SubclassOfEntity1) {
     *     SubclassOfEntity1 original = field;
     *     SubclassOfEntity1 clone = new SubclassOfEntity1();
     *
     *     // shallowly clone fields using writeShallowCloneInstructions()
     *     // for any deeply cloned field, do recursion on it
     * } else if (original instanceof SubclassOfEntity2) {
     *     // ...
     * }
     * </pre>
     * 
     * @param bytecodeCreator
     * @param solutionDescriptor
     * @param deeplyClonedFieldClass
     * @param type
     * @param toClone
     * @param cloneResultHolder
     */
    private static void writeDeepCloneInstructions(BytecodeCreator bytecodeCreator,
            GizmoSolutionOrEntityDescriptor solutionDescriptor,
            Class<?> deeplyClonedFieldClass, java.lang.reflect.Type type, ResultHandle toClone,
            AssignableResultHandle cloneResultHolder, ResultHandle createdCloneMap) {
        BranchResult isNull = bytecodeCreator.ifNull(toClone);

        BytecodeCreator isNullBranch = isNull.trueBranch();
        isNullBranch.assign(cloneResultHolder, isNullBranch.loadNull());

        BytecodeCreator isNotNullBranch = isNull.falseBranch();

        if (solutionDescriptor.getSolutionDescriptor().getSolutionClass().isAssignableFrom(deeplyClonedFieldClass)) {
            ResultHandle clone = isNotNullBranch.invokeStaticMethod(
                    MethodDescriptor.ofMethod(
                            GizmoSolutionClonerFactory.getGeneratedClassName(solutionDescriptor.getSolutionDescriptor()),
                            "cloneSolutionRun", solutionDescriptor.getSolutionDescriptor().getSolutionClass(),
                            solutionDescriptor.getSolutionDescriptor().getSolutionClass(), Map.class),
                    toClone,
                    createdCloneMap);
            isNotNullBranch.assign(cloneResultHolder, clone);
        } else if (Collection.class.isAssignableFrom(deeplyClonedFieldClass)) {
            // Clone collection
            writeDeepCloneCollectionInstructions(isNotNullBranch, solutionDescriptor, deeplyClonedFieldClass, type,
                    toClone, cloneResultHolder, createdCloneMap);
        } else if (Map.class.isAssignableFrom(deeplyClonedFieldClass)) {
            // Clone map
            writeDeepCloneMapInstructions(isNotNullBranch, solutionDescriptor, deeplyClonedFieldClass, type,
                    toClone, cloneResultHolder, createdCloneMap);
        } else if (deeplyClonedFieldClass.isArray()) {
            // Clone array
            writeDeepCloneArrayInstructions(isNotNullBranch, solutionDescriptor, deeplyClonedFieldClass,
                    toClone, cloneResultHolder, createdCloneMap);
        } else {
            // Clone entity
            writeDeepCloneEntityInstructions(isNotNullBranch, solutionDescriptor, deeplyClonedFieldClass,
                    toClone, cloneResultHolder, createdCloneMap);
        }
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // For a Collection
     * Collection clone = new ActualCollectionType();
     * Iterator iterator = toClone.iterator();
     * while (iterator.hasNext()) {
     *     Object toCloneElement = iterator.next();
     *     Object nextClone = (result from recursion on toCloneElement);
     *     clone.add(nextClone);
     * }
     * cloneResultHolder = clone;
     * </pre>
     **/
    private static void writeDeepCloneCollectionInstructions(BytecodeCreator bytecodeCreator,
            GizmoSolutionOrEntityDescriptor solutionDescriptor,
            Class<?> deeplyClonedFieldClass, java.lang.reflect.Type type, ResultHandle toClone,
            AssignableResultHandle cloneResultHolder, ResultHandle createdCloneMap) {
        // Clone collection
        AssignableResultHandle cloneCollection = bytecodeCreator.createVariable(deeplyClonedFieldClass);

        ResultHandle size = bytecodeCreator
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Collection.class, "size", int.class), toClone);

        if (List.class.isAssignableFrom(deeplyClonedFieldClass)) {
            bytecodeCreator.assign(cloneCollection,
                    bytecodeCreator.newInstance(MethodDescriptor.ofConstructor(ArrayList.class, int.class), size));
        } else if (Set.class.isAssignableFrom(deeplyClonedFieldClass)) {
            ResultHandle isSortedSet = bytecodeCreator.instanceOf(toClone, SortedSet.class);
            BranchResult isSortedSetBranchResult = bytecodeCreator.ifTrue(isSortedSet);
            BytecodeCreator isSortedSetBranch = isSortedSetBranchResult.trueBranch();
            ResultHandle setComparator = isSortedSetBranch
                    .invokeInterfaceMethod(MethodDescriptor.ofMethod(SortedSet.class,
                            "comparator", Comparator.class), toClone);
            isSortedSetBranch.assign(cloneCollection,
                    isSortedSetBranch.newInstance(MethodDescriptor.ofConstructor(TreeSet.class, Comparator.class),
                            setComparator));
            BytecodeCreator isNotSortedSetBranch = isSortedSetBranchResult.falseBranch();
            isNotSortedSetBranch.assign(cloneCollection,
                    isNotSortedSetBranch.newInstance(MethodDescriptor.ofConstructor(LinkedHashSet.class, int.class), size));
        } else {
            // field is probably of type collection
            ResultHandle isSet = bytecodeCreator.instanceOf(toClone, Set.class);
            BranchResult isSetBranchResult = bytecodeCreator.ifTrue(isSet);
            BytecodeCreator isSetBranch = isSetBranchResult.trueBranch();
            ResultHandle isSortedSet = isSetBranch.instanceOf(toClone, SortedSet.class);
            BranchResult isSortedSetBranchResult = isSetBranch.ifTrue(isSortedSet);
            BytecodeCreator isSortedSetBranch = isSortedSetBranchResult.trueBranch();
            ResultHandle setComparator = isSortedSetBranch
                    .invokeInterfaceMethod(MethodDescriptor.ofMethod(SortedSet.class,
                            "comparator", Comparator.class), toClone);
            isSortedSetBranch.assign(cloneCollection,
                    isSortedSetBranch.newInstance(MethodDescriptor.ofConstructor(TreeSet.class, Comparator.class),
                            setComparator));
            BytecodeCreator isNotSortedSetBranch = isSortedSetBranchResult.falseBranch();
            isNotSortedSetBranch.assign(cloneCollection,
                    isNotSortedSetBranch.newInstance(MethodDescriptor.ofConstructor(LinkedHashSet.class, int.class), size));
            // Default to ArrayList
            BytecodeCreator isNotSetBranch = isSetBranchResult.falseBranch();
            isNotSetBranch.assign(cloneCollection,
                    isNotSetBranch.newInstance(MethodDescriptor.ofConstructor(ArrayList.class, int.class), size));
        }
        ResultHandle iterator = bytecodeCreator
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterable.class, "iterator", Iterator.class), toClone);

        BytecodeCreator whileLoopBlock = bytecodeCreator.whileLoop(conditionBytecode -> {
            ResultHandle hasNext = conditionBytecode
                    .invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "hasNext", boolean.class), iterator);
            return conditionBytecode.ifTrue(hasNext);
        }).block();

        Class<?> elementClass;
        java.lang.reflect.Type elementClassType;
        if (type instanceof ParameterizedType) {
            // Assume Collection follow Collection<T> convention of first type argument = element class
            elementClassType = ((ParameterizedType) type).getActualTypeArguments()[0];
            if (elementClassType instanceof Class) {
                elementClass = (Class<?>) elementClassType;
            } else if (elementClassType instanceof ParameterizedType) {
                elementClass = (Class<?>) ((ParameterizedType) elementClassType).getRawType();
            } else {
                throw new IllegalStateException("Unhandled type " + elementClassType + ".");
            }
        } else {
            throw new IllegalStateException("Cannot infer element type for Collection type (" + type + ").");
        }

        // Odd case of member get and set being on different classes; will work as we only
        // use get on the original and set on the clone.
        ResultHandle next =
                whileLoopBlock.invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "next", Object.class), iterator);
        final AssignableResultHandle clonedElement = whileLoopBlock.createVariable(elementClass);
        writeDeepCloneInstructions(whileLoopBlock, solutionDescriptor,
                elementClass, elementClassType, next, clonedElement, createdCloneMap);
        whileLoopBlock.invokeInterfaceMethod(MethodDescriptor.ofMethod(Collection.class, "add", boolean.class, Object.class),
                cloneCollection,
                clonedElement);
        bytecodeCreator.assign(cloneResultHolder, cloneCollection);
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // For a Map
     * Map clone = new ActualMapType();
     * Iterator iterator = toClone.entrySet().iterator();
     * while (iterator.hasNext()) {
     *      Entry next = iterator.next();
     *      Object toCloneValue = next.getValue();
     *      nextClone = (result from recursion on toCloneValue);
     *      clone.put(next.getKey(), nextClone);
     * }
     * cloneResultHolder = clone;
     * </pre>
     **/
    private static void writeDeepCloneMapInstructions(BytecodeCreator bytecodeCreator,
            GizmoSolutionOrEntityDescriptor solutionDescriptor,
            Class<?> deeplyClonedFieldClass, java.lang.reflect.Type type, ResultHandle toClone,
            AssignableResultHandle cloneResultHolder, ResultHandle createdCloneMap) {
        Class<?> holderClass = deeplyClonedFieldClass;
        try {
            holderClass.getConstructor();
        } catch (NoSuchMethodException e) {
            if (LinkedHashMap.class.isAssignableFrom(holderClass)) {
                holderClass = LinkedHashMap.class;
            } else if (ConcurrentHashMap.class.isAssignableFrom(holderClass)) {
                holderClass = ConcurrentHashMap.class;
            } else {
                // Default to LinkedHashMap
                holderClass = LinkedHashMap.class;
            }
        }

        ResultHandle cloneMap;
        ResultHandle size =
                bytecodeCreator.invokeInterfaceMethod(MethodDescriptor.ofMethod(Map.class, "size", int.class), toClone);
        ResultHandle entrySet = bytecodeCreator
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Map.class, "entrySet", Set.class), toClone);
        ResultHandle iterator = bytecodeCreator
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterable.class, "iterator", Iterator.class), entrySet);
        try {
            holderClass.getConstructor(int.class);
            cloneMap = bytecodeCreator.newInstance(MethodDescriptor.ofConstructor(holderClass, int.class), size);
        } catch (NoSuchMethodException e) {
            cloneMap = bytecodeCreator.newInstance(MethodDescriptor.ofConstructor(holderClass));
        }

        BytecodeCreator whileLoopBlock = bytecodeCreator.whileLoop(conditionBytecode -> {
            ResultHandle hasNext = conditionBytecode
                    .invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "hasNext", boolean.class), iterator);
            return conditionBytecode.ifTrue(hasNext);
        }).block();

        Class<?> keyClass;
        Class<?> elementClass;
        java.lang.reflect.Type keyType;
        java.lang.reflect.Type elementClassType;
        if (type instanceof ParameterizedType) {
            // Assume Map follow Map<K,V> convention of second type argument = value class
            keyType = ((ParameterizedType) type).getActualTypeArguments()[0];
            elementClassType = ((ParameterizedType) type).getActualTypeArguments()[1];
            if (elementClassType instanceof Class) {
                elementClass = (Class<?>) elementClassType;
            } else if (elementClassType instanceof ParameterizedType) {
                elementClass = (Class<?>) ((ParameterizedType) elementClassType).getRawType();
            } else {
                throw new IllegalStateException("Unhandled type " + elementClassType + ".");
            }

            if (keyType instanceof Class) {
                keyClass = (Class<?>) keyType;
            } else if (keyType instanceof ParameterizedType) {
                keyClass = (Class<?>) ((ParameterizedType) keyType).getRawType();
            } else {
                throw new IllegalStateException("Unhandled type " + keyType + ".");
            }
        } else {
            throw new IllegalStateException("Cannot infer element type for Map type (" + type + ").");
        }

        List<Class<?>> entitySubclasses = solutionDescriptor.getSolutionDescriptor().getEntityClassSet().stream()
                .filter(keyClass::isAssignableFrom).collect(Collectors.toList());
        ResultHandle entry = whileLoopBlock
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "next", Object.class), iterator);
        ResultHandle toCloneValue = whileLoopBlock
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Map.Entry.class, "getValue", Object.class), entry);

        final AssignableResultHandle clonedElement = whileLoopBlock.createVariable(elementClass);
        writeDeepCloneInstructions(whileLoopBlock, solutionDescriptor,
                elementClass, elementClassType, toCloneValue, clonedElement, createdCloneMap);

        ResultHandle key = whileLoopBlock
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(Map.Entry.class, "getKey", Object.class), entry);
        if (!entitySubclasses.isEmpty()) {
            AssignableResultHandle keyCloneResultHolder = whileLoopBlock.createVariable(keyClass);
            writeDeepCloneEntityInstructions(whileLoopBlock, solutionDescriptor, keyClass,
                    key, keyCloneResultHolder, createdCloneMap);
            whileLoopBlock.invokeInterfaceMethod(
                    PUT_METHOD,
                    cloneMap, keyCloneResultHolder, clonedElement);
        } else {
            whileLoopBlock.invokeInterfaceMethod(
                    PUT_METHOD,
                    cloneMap, key, clonedElement);
        }

        bytecodeCreator.assign(cloneResultHolder, cloneMap);
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // For an array
     * Object[] clone = new Object[toClone.length];
     *
     * for (int i = 0; i < original.length; i++) {
     *     clone[i] = (result from recursion on toClone[i]);
     * }
     * cloneResultHolder = clone;
     * </pre>
     **/
    private static void writeDeepCloneArrayInstructions(BytecodeCreator bytecodeCreator,
            GizmoSolutionOrEntityDescriptor solutionDescriptor,
            Class<?> deeplyClonedFieldClass, ResultHandle toClone, AssignableResultHandle cloneResultHolder,
            ResultHandle createdCloneMap) {
        // Clone array
        Class<?> arrayComponent = deeplyClonedFieldClass.getComponentType();
        ResultHandle arrayLength = bytecodeCreator.arrayLength(toClone);
        ResultHandle arrayClone = bytecodeCreator.newArray(arrayComponent, arrayLength);
        AssignableResultHandle iterations = bytecodeCreator.createVariable(int.class);
        bytecodeCreator.assign(iterations, bytecodeCreator.load(0));
        BytecodeCreator whileLoopBlock = bytecodeCreator
                .whileLoop(conditionBytecode -> conditionBytecode.ifIntegerLessThan(iterations, arrayLength))
                .block();
        ResultHandle toCloneElement = whileLoopBlock.readArrayValue(toClone, iterations);
        AssignableResultHandle clonedElement = whileLoopBlock.createVariable(arrayComponent);

        writeDeepCloneInstructions(whileLoopBlock, solutionDescriptor, arrayComponent,
                arrayComponent, toCloneElement, clonedElement, createdCloneMap);
        whileLoopBlock.writeArrayValue(arrayClone, iterations, clonedElement);
        whileLoopBlock.assign(iterations, whileLoopBlock.increment(iterations));

        bytecodeCreator.assign(cloneResultHolder, arrayClone);
    }

    /**
     * Writes the following code:
     *
     * <pre>
     * // For an entity
     * if (toClone instanceof SubclassOfEntity1) {
     *     SubclassOfEntity1 clone = new SubclassOfEntity1();
     *
     *     // shallowly clone fields using writeShallowCloneInstructions()
     *     // for any deeply cloned field, do recursion on it
     *     cloneResultHolder = clone;
     * } else if (toClone instanceof SubclassOfEntity2) {
     *     // ...
     * }
     * // ...
     * else if (toClone instanceof SubclassOfEntityN) {
     *     // ...
     * } else {
     *     cloneResultHolder = toClone;
     * }
     * </pre>
     **/
    private static void writeDeepCloneEntityInstructions(BytecodeCreator bytecodeCreator,
            GizmoSolutionOrEntityDescriptor solutionDescriptor,
            Class<?> deeplyClonedFieldClass, ResultHandle toClone, AssignableResultHandle cloneResultHolder,
            ResultHandle createdCloneMap) {
        // Clone entity
        List<Class<?>> entitySubclasses = solutionDescriptor.getSolutionDescriptor().getEntityClassSet().stream()
                .filter(deeplyClonedFieldClass::isAssignableFrom).collect(Collectors.toList());
        if (entitySubclasses.isEmpty()) {
            // Not an entity, can shallow copy
            bytecodeCreator.assign(cloneResultHolder, toClone);
        } else {
            BytecodeCreator currentBranch = bytecodeCreator;
            for (Class<?> entitySubclass : entitySubclasses) {
                ResultHandle isInstance = currentBranch.instanceOf(toClone, entitySubclass);
                BranchResult isInstanceBranchResult = currentBranch.ifTrue(isInstance);
                BytecodeCreator isInstanceBranch = isInstanceBranchResult.trueBranch();
                ResultHandle cloneObj = isInstanceBranch.invokeStaticMethod(
                        MethodDescriptor.ofMethod(
                                GizmoSolutionClonerFactory.getGeneratedClassName(solutionDescriptor.getSolutionDescriptor()),
                                getEntityHelperMethodName(entitySubclass), entitySubclass, entitySubclass, Map.class),
                        toClone, createdCloneMap);
                isInstanceBranch.assign(cloneResultHolder, cloneObj);
                currentBranch = isInstanceBranchResult.falseBranch();
            }
            // currentBranch is when it none of the entity subclasses,
            // so we can shallow clone
            final BytecodeCreator notAnEntity = currentBranch;
            notAnEntity.assign(cloneResultHolder, toClone);
        }
    }

    private static String getEntityHelperMethodName(Class<?> entityClass) {
        return "$clone" + entityClass.getName().replace('.', '_');
    }

    // To prevent stack overflow on chained models
    private static void createEntityHelperMethod(ClassCreator classCreator,
            Class<?> entityClass,
            SolutionDescriptor<?> solutionDescriptor,
            Map<Class<?>, GizmoSolutionOrEntityDescriptor> memoizedSolutionOrEntityDescriptorMap) {
        MethodCreator methodCreator =
                classCreator.getMethodCreator(getEntityHelperMethodName(entityClass), entityClass, entityClass, Map.class);
        methodCreator.setModifiers(Modifier.STATIC | Modifier.PRIVATE);

        GizmoSolutionOrEntityDescriptor entityDescriptor = memoizedSolutionOrEntityDescriptorMap.computeIfAbsent(entityClass,
                (key) -> new GizmoSolutionOrEntityDescriptor(solutionDescriptor, entityClass));

        ResultHandle toClone = methodCreator.getMethodParam(0);
        ResultHandle cloneMap = methodCreator.getMethodParam(1);
        ResultHandle maybeClone = methodCreator.invokeInterfaceMethod(
                GET_METHOD, cloneMap, toClone);
        BranchResult hasCloneBranchResult = methodCreator.ifNotNull(maybeClone);
        BytecodeCreator hasCloneBranch = hasCloneBranchResult.trueBranch();
        hasCloneBranch.returnValue(maybeClone);

        BytecodeCreator noCloneBranch = hasCloneBranchResult.falseBranch();

        ResultHandle cloneObj = noCloneBranch.newInstance(MethodDescriptor.ofConstructor(entityClass));
        noCloneBranch.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(Map.class, "put", Object.class, Object.class, Object.class),
                cloneMap, toClone, cloneObj);

        for (GizmoMemberDescriptor shallowlyClonedField : entityDescriptor.getShallowClonedMemberDescriptors()) {
            writeShallowCloneInstructions(entityDescriptor, noCloneBranch, shallowlyClonedField, toClone, cloneObj, cloneMap);
        }

        for (Field deeplyClonedField : entityDescriptor.getDeepClonedFields()) {
            GizmoMemberDescriptor gizmoMemberDescriptor =
                    entityDescriptor.getMemberDescriptorForField(deeplyClonedField);
            ResultHandle subfieldValue = gizmoMemberDescriptor.readMemberValue(noCloneBranch, toClone);

            AssignableResultHandle cloneValue = noCloneBranch.createVariable(deeplyClonedField.getType());
            writeDeepCloneInstructions(noCloneBranch, entityDescriptor,
                    deeplyClonedField.getType(), gizmoMemberDescriptor.getType(), subfieldValue, cloneValue, cloneMap);

            if (!gizmoMemberDescriptor.writeMemberValue(noCloneBranch, cloneObj, cloneValue)) {
                throw new IllegalStateException("The member (" + gizmoMemberDescriptor.getName() + ") of class (" +
                        gizmoMemberDescriptor.getDeclaringClassName() + ") does not have a setter.");
            }
        }

        noCloneBranch.returnValue(cloneObj);
    }

    // ************************************************************************
    // Private constructor
    // ************************************************************************

    private GizmoSolutionClonerImplementor() {

    }
}
