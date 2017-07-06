package io.crnk.core.engine.internal.utils;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * <p> Utility methods focusing on type inspection, particularly with regard to
 * generics. </p>
 */
public class TypeUtils {

	/**
	 * <p>{@code TypeUtils} instances should NOT be constructed in standard
	 * programming. Instead, the class should be used as
	 * {@code TypeUtils.isAssignable(cls, toClass)}.</p> <p>This
	 * constructor is public to permit tools that require a JavaBean instance to
	 * operate.</p>
	 */
	public TypeUtils() {
		super();
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target type
	 * following the Java generics rules. If both types are {@link Class}
	 * objects, the method returns the result of
	 * {@link ClassUtils#isAssignable(Class, Class)}.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toType the target type
	 * @return {@code true} if {@code type} is assignable to {@code toType}.
	 */
	public static boolean isAssignable(final Type type, final Type toType) {
		return isAssignable(type, toType, null);
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target type
	 * following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toType the target type
	 * @param typeVarAssigns optional map of type variable assignments
	 * @return {@code true} if {@code type} is assignable to {@code toType}.
	 */
	private static boolean isAssignable(final Type type, final Type toType,
			final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (toType == null || toType instanceof Class<?>) {
			return isAssignable(type, (Class<?>) toType);
		}

		if (toType instanceof ParameterizedType) {
			return isAssignable(type, (ParameterizedType) toType, typeVarAssigns);
		}

		if (toType instanceof GenericArrayType) {
			return isAssignable(type, (GenericArrayType) toType, typeVarAssigns);
		}

		if (toType instanceof WildcardType) {
			return isAssignable(type, (WildcardType) toType, typeVarAssigns);
		}

		if (toType instanceof TypeVariable<?>) {
			return isAssignable(type, (TypeVariable<?>) toType, typeVarAssigns);
		}

		throw new IllegalStateException("found an unhandled type: " + toType);
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target class
	 * following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toClass the target class
	 * @return {@code true} if {@code type} is assignable to {@code toClass}.
	 */
	private static boolean isAssignable(final Type type, final Class<?> toClass) {
		if (type == null) {
			// consistency with ClassUtils.isAssignable() behavior
			return toClass == null || !toClass.isPrimitive();
		}

		// only a null type can be assigned to null type which
		// would have cause the previous to return true
		if (toClass == null) {
			return false;
		}

		// all types are assignable to themselves
		if (toClass.equals(type)) {
			return true;
		}

		if (type instanceof Class<?>) {
			// just comparing two classes
			return ClassUtils.isAssignable((Class<?>) type, toClass);
		}

		if (type instanceof ParameterizedType) {
			// only have to compare the raw type to the class
			return isAssignable(getRawType((ParameterizedType) type), toClass);
		}

		// *
		if (type instanceof TypeVariable<?>) {
			// if any of the bounds are assignable to the class, then the
			// type is assignable to the class.
			for (final Type bound : ((TypeVariable<?>) type).getBounds()) {
				if (isAssignable(bound, toClass)) {
					return true;
				}
			}

			return false;
		}

		// the only classes to which a generic array type can be assigned
		// are class Object and array classes
		if (type instanceof GenericArrayType) {
			return toClass.equals(Object.class)
					|| toClass.isArray()
					&& isAssignable(((GenericArrayType) type).getGenericComponentType(), toClass
					.getComponentType());
		}

		// wildcard types are not assignable to a class (though one would think
		// "? super Object" would be assignable to Object)
		if (type instanceof WildcardType) {
			return false;
		}

		throw new IllegalStateException("found an unhandled type: " + type);
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target
	 * parameterized type following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toParameterizedType the target parameterized type
	 * @param typeVarAssigns a map with type variables
	 * @return {@code true} if {@code type} is assignable to {@code toType}.
	 */
	private static boolean isAssignable(final Type type, final ParameterizedType toParameterizedType,
			final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (type == null) {
			return true;
		}

		// only a null type can be assigned to null type which
		// would have cause the previous to return true
		if (toParameterizedType == null) {
			return false;
		}

		// all types are assignable to themselves
		if (toParameterizedType.equals(type)) {
			return true;
		}

		// get the target type's raw type
		final Class<?> toClass = getRawType(toParameterizedType);
		// get the subject type's type arguments including owner type arguments
		// and supertype arguments up to and including the target class.
		final Map<TypeVariable<?>, Type> fromTypeVarAssigns = getTypeArguments(type, toClass, null);

		// null means the two types are not compatible
		if (fromTypeVarAssigns == null) {
			return false;
		}

		// compatible types, but there's no type arguments. this is equivalent
		// to comparing Map< ?, ? > to Map, and raw types are always assignable
		// to parameterized types.
		if (fromTypeVarAssigns.isEmpty()) {
			return true;
		}

		// get the target type's type arguments including owner type arguments
		final Map<TypeVariable<?>, Type> toTypeVarAssigns = getTypeArguments(toParameterizedType,
				toClass, typeVarAssigns);

		// now to check each type argument
		for (final TypeVariable<?> var : toTypeVarAssigns.keySet()) {
			final Type toTypeArg = unrollVariableAssignments(var, toTypeVarAssigns);
			final Type fromTypeArg = unrollVariableAssignments(var, fromTypeVarAssigns);

			if (toTypeArg == null && fromTypeArg instanceof Class) {
				continue;
			}

			// parameters must either be absent from the subject type, within
			// the bounds of the wildcard type, or be an exact match to the
			// parameters of the target type.
			if (fromTypeArg != null
					&& !toTypeArg.equals(fromTypeArg)
					&& !(toTypeArg instanceof WildcardType && isAssignable(fromTypeArg, toTypeArg,
					typeVarAssigns))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Look up {@code var} in {@code typeVarAssigns} <em>transitively</em>,
	 * i.e. keep looking until the value found is <em>not</em> a type variable.
	 * @param var the type variable to look up
	 * @param typeVarAssigns the map used for the look up
	 * @return Type or {@code null} if some variable was not in the map
	 * @since 3.2
	 */
	private static Type unrollVariableAssignments(TypeVariable<?> var, final Map<TypeVariable<?>, Type> typeVarAssigns) {
		Type result;
		do {
			result = typeVarAssigns.get(var);
			if (result instanceof TypeVariable<?> && !result.equals(var)) {
				var = (TypeVariable<?>) result;
				continue;
			}
			break;
		} while (true);
		return result;
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target
	 * generic array type following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toGenericArrayType the target generic array type
	 * @param typeVarAssigns a map with type variables
	 * @return {@code true} if {@code type} is assignable to
	 * {@code toGenericArrayType}.
	 */
	private static boolean isAssignable(final Type type, final GenericArrayType toGenericArrayType,
			final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (type == null) {
			return true;
		}

		// only a null type can be assigned to null type which
		// would have cause the previous to return true
		if (toGenericArrayType == null) {
			return false;
		}

		// all types are assignable to themselves
		if (toGenericArrayType.equals(type)) {
			return true;
		}

		final Type toComponentType = toGenericArrayType.getGenericComponentType();

		if (type instanceof Class<?>) {
			final Class<?> cls = (Class<?>) type;

			// compare the component types
			return cls.isArray()
					&& isAssignable(cls.getComponentType(), toComponentType, typeVarAssigns);
		}

		if (type instanceof GenericArrayType) {
			// compare the component types
			return isAssignable(((GenericArrayType) type).getGenericComponentType(),
					toComponentType, typeVarAssigns);
		}

		if (type instanceof WildcardType) {
			// so long as one of the upper bounds is assignable, it's good
			for (final Type bound : getImplicitUpperBounds((WildcardType) type)) {
				if (isAssignable(bound, toGenericArrayType)) {
					return true;
				}
			}

			return false;
		}

		if (type instanceof TypeVariable<?>) {
			// probably should remove the following logic and just return false.
			// type variables cannot specify arrays as bounds.
			for (final Type bound : getImplicitBounds((TypeVariable<?>) type)) {
				if (isAssignable(bound, toGenericArrayType)) {
					return true;
				}
			}

			return false;
		}

		if (type instanceof ParameterizedType) {
			// the raw type of a parameterized type is never an array or
			// generic array, otherwise the declaration would look like this:
			// Collection[]< ? extends String > collection;
			return false;
		}

		throw new IllegalStateException("found an unhandled type: " + type);
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target
	 * wildcard type following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toWildcardType the target wildcard type
	 * @param typeVarAssigns a map with type variables
	 * @return {@code true} if {@code type} is assignable to
	 * {@code toWildcardType}.
	 */
	private static boolean isAssignable(final Type type, final WildcardType toWildcardType,
			final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (type == null) {
			return true;
		}

		// only a null type can be assigned to null type which
		// would have cause the previous to return true
		if (toWildcardType == null) {
			return false;
		}

		// all types are assignable to themselves
		if (toWildcardType.equals(type)) {
			return true;
		}

		final Type[] toUpperBounds = getImplicitUpperBounds(toWildcardType);
		final Type[] toLowerBounds = getImplicitLowerBounds(toWildcardType);

		if (type instanceof WildcardType) {
			final WildcardType wildcardType = (WildcardType) type;
			final Type[] upperBounds = getImplicitUpperBounds(wildcardType);
			final Type[] lowerBounds = getImplicitLowerBounds(wildcardType);

			for (Type toBound : toUpperBounds) {
				// if there are assignments for unresolved type variables,
				// now's the time to substitute them.
				toBound = substituteTypeVariables(toBound, typeVarAssigns);

				// each upper bound of the subject type has to be assignable to
				// each
				// upper bound of the target type
				for (final Type bound : upperBounds) {
					if (!isAssignable(bound, toBound, typeVarAssigns)) {
						return false;
					}
				}
			}

			for (Type toBound : toLowerBounds) {
				// if there are assignments for unresolved type variables,
				// now's the time to substitute them.
				toBound = substituteTypeVariables(toBound, typeVarAssigns);

				// each lower bound of the target type has to be assignable to
				// each
				// lower bound of the subject type
				for (final Type bound : lowerBounds) {
					if (!isAssignable(toBound, bound, typeVarAssigns)) {
						return false;
					}
				}
			}
			return true;
		}

		for (final Type toBound : toUpperBounds) {
			// if there are assignments for unresolved type variables,
			// now's the time to substitute them.
			if (!isAssignable(type, substituteTypeVariables(toBound, typeVarAssigns),
					typeVarAssigns)) {
				return false;
			}
		}

		for (final Type toBound : toLowerBounds) {
			// if there are assignments for unresolved type variables,
			// now's the time to substitute them.
			if (!isAssignable(substituteTypeVariables(toBound, typeVarAssigns), type,
					typeVarAssigns)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * <p>Checks if the subject type may be implicitly cast to the target type
	 * variable following the Java generics rules.</p>
	 *
	 * @param type the subject type to be assigned to the target type
	 * @param toTypeVariable the target type variable
	 * @param typeVarAssigns a map with type variables
	 * @return {@code true} if {@code type} is assignable to
	 * {@code toTypeVariable}.
	 */
	private static boolean isAssignable(final Type type, final TypeVariable<?> toTypeVariable,
			final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (type == null) {
			return true;
		}

		// only a null type can be assigned to null type which
		// would have cause the previous to return true
		if (toTypeVariable == null) {
			return false;
		}

		// all types are assignable to themselves
		if (toTypeVariable.equals(type)) {
			return true;
		}

		if (type instanceof TypeVariable<?>) {
			// a type variable is assignable to another type variable, if
			// and only if the former is the latter, extends the latter, or
			// is otherwise a descendant of the latter.
			final Type[] bounds = getImplicitBounds((TypeVariable<?>) type);

			for (final Type bound : bounds) {
				if (isAssignable(bound, toTypeVariable, typeVarAssigns)) {
					return true;
				}
			}
		}

		if (type instanceof Class<?> || type instanceof ParameterizedType
				|| type instanceof GenericArrayType || type instanceof WildcardType) {
			return false;
		}

		throw new IllegalStateException("found an unhandled type: " + type);
	}

	/**
	 * <p>Find the mapping for {@code type} in {@code typeVarAssigns}.</p>
	 *
	 * @param type the type to be replaced
	 * @param typeVarAssigns the map with type variables
	 * @return the replaced type
	 * @throws IllegalArgumentException if the type cannot be substituted
	 */
	private static Type substituteTypeVariables(final Type type, final Map<TypeVariable<?>, Type> typeVarAssigns) {
		if (type instanceof TypeVariable<?> && typeVarAssigns != null) {
			final Type replacementType = typeVarAssigns.get(type);

			if (replacementType == null) {
				throw new IllegalArgumentException("missing assignment type for type variable "
						+ type);
			}
			return replacementType;
		}
		return type;
	}

	/**
	 * <p>Retrieves all the type arguments for this parameterized type
	 * including owner hierarchy arguments such as
	 * {@code Outer<K,V>.Inner<T>.DeepInner<E>} .
	 * The arguments are returned in a
	 * {@link Map} specifying the argument type for each {@link TypeVariable}.
	 * </p>
	 *
	 * @param type specifies the subject parameterized type from which to
	 *             harvest the parameters.
	 * @return a {@code Map} of the type arguments to their respective type
	 * variables.
	 */
	public static Map<TypeVariable<?>, Type> getTypeArguments(final ParameterizedType type) {
		return getTypeArguments(type, getRawType(type), null);
	}

	/**
	 * <p>Gets the type arguments of a class/interface based on a subtype. For
	 * instance, this method will determine that both of the parameters for the
	 * interface {@link Map} are {@link Object} for the subtype
	 * {@link java.util.Properties Properties} even though the subtype does not
	 * directly implement the {@code Map} interface.</p>
	 * <p>This method returns {@code null} if {@code type} is not assignable to
	 * {@code toClass}. It returns an empty map if none of the classes or
	 * interfaces in its inheritance hierarchy specify any type arguments.</p>
	 * <p>A side effect of this method is that it also retrieves the type
	 * arguments for the classes and interfaces that are part of the hierarchy
	 * between {@code type} and {@code toClass}. So with the above
	 * example, this method will also determine that the type arguments for
	 * {@link java.util.Hashtable Hashtable} are also both {@code Object}.
	 * In cases where the interface specified by {@code toClass} is
	 * (indirectly) implemented more than once (e.g. where {@code toClass}
	 * specifies the interface {@link java.lang.Iterable Iterable} and
	 * {@code type} specifies a parameterized type that implements both
	 * {@link java.util.Set Set} and {@link java.util.Collection Collection}),
	 * this method will look at the inheritance hierarchy of only one of the
	 * implementations/subclasses; the first interface encountered that isn't a
	 * subinterface to one of the others in the {@code type} to
	 * {@code toClass} hierarchy.</p>
	 *
	 * @param type the type from which to determine the type parameters of
	 * {@code toClass}
	 * @param toClass the class whose type parameters are to be determined based
	 * on the subtype {@code type}
	 * @return a {@code Map} of the type assignments for the type variables in
	 * each type in the inheritance hierarchy from {@code type} to
	 * {@code toClass} inclusive.
	 */
	public static Map<TypeVariable<?>, Type> getTypeArguments(final Type type, final Class<?> toClass) {
		return getTypeArguments(type, toClass, null);
	}

	/**
	 * <p>Return a map of the type arguments of {@code type} in the context of {@code toClass}.</p>
	 *
	 * @param type the type in question
	 * @param toClass the class
	 * @param subtypeVarAssigns a map with type variables
	 * @return the {@code Map} with type arguments
	 */
	private static Map<TypeVariable<?>, Type> getTypeArguments(final Type type, final Class<?> toClass,
			final Map<TypeVariable<?>, Type> subtypeVarAssigns) {
		if (type instanceof Class<?>) {
			return getTypeArguments((Class<?>) type, toClass, subtypeVarAssigns);
		}

		if (type instanceof ParameterizedType) {
			return getTypeArguments((ParameterizedType) type, toClass, subtypeVarAssigns);
		}

		if (type instanceof GenericArrayType) {
			return getTypeArguments(((GenericArrayType) type).getGenericComponentType(), toClass
					.isArray() ? toClass.getComponentType() : toClass, subtypeVarAssigns);
		}

		// since wildcard types are not assignable to classes, should this just
		// return null?
		if (type instanceof WildcardType) {
			for (final Type bound : getImplicitUpperBounds((WildcardType) type)) {
				// find the first bound that is assignable to the target class
				if (isAssignable(bound, toClass)) {
					return getTypeArguments(bound, toClass, subtypeVarAssigns);
				}
			}

			return null;
		}

		if (type instanceof TypeVariable<?>) {
			for (final Type bound : getImplicitBounds((TypeVariable<?>) type)) {
				// find the first bound that is assignable to the target class
				if (isAssignable(bound, toClass)) {
					return getTypeArguments(bound, toClass, subtypeVarAssigns);
				}
			}

			return null;
		}
		throw new IllegalStateException("found an unhandled type: " + type);
	}

	/**
	 * <p>Return a map of the type arguments of a parameterized type in the context of {@code toClass}.</p>
	 *
	 * @param parameterizedType the parameterized type
	 * @param toClass the class
	 * @param subtypeVarAssigns a map with type variables
	 * @return the {@code Map} with type arguments
	 */
	private static Map<TypeVariable<?>, Type> getTypeArguments(
			final ParameterizedType parameterizedType, final Class<?> toClass,
			final Map<TypeVariable<?>, Type> subtypeVarAssigns) {
		final Class<?> cls = getRawType(parameterizedType);

		// make sure they're assignable
		if (!isAssignable(cls, toClass)) {
			return null;
		}

		final Type ownerType = parameterizedType.getOwnerType();
		Map<TypeVariable<?>, Type> typeVarAssigns;

		if (ownerType instanceof ParameterizedType) {
			// get the owner type arguments first
			final ParameterizedType parameterizedOwnerType = (ParameterizedType) ownerType;
			typeVarAssigns = getTypeArguments(parameterizedOwnerType,
					getRawType(parameterizedOwnerType), subtypeVarAssigns);
		} else {
			// no owner, prep the type variable assignments map
			typeVarAssigns = subtypeVarAssigns == null ? new HashMap<TypeVariable<?>, Type>()
					: new HashMap<>(subtypeVarAssigns);
		}

		// get the subject parameterized type's arguments
		final Type[] typeArgs = parameterizedType.getActualTypeArguments();
		// and get the corresponding type variables from the raw class
		final TypeVariable<?>[] typeParams = cls.getTypeParameters();

		// map the arguments to their respective type variables
		for (int i = 0; i < typeParams.length; i++) {
			final Type typeArg = typeArgs[i];
			typeVarAssigns.put(typeParams[i], typeVarAssigns.containsKey(typeArg) ? typeVarAssigns
					.get(typeArg) : typeArg);
		}

		if (toClass.equals(cls)) {
			// target class has been reached. Done.
			return typeVarAssigns;
		}

		// walk the inheritance hierarchy until the target class is reached
		return getTypeArguments(getClosestParentType(cls, toClass), toClass, typeVarAssigns);
	}

	/**
	 * <p>Return a map of the type arguments of a class in the context of {@code toClass}.</p>
	 *
	 * @param cls the class in question
	 * @param toClass the context class
	 * @param subtypeVarAssigns a map with type variables
	 * @return the {@code Map} with type arguments
	 */
	private static Map<TypeVariable<?>, Type> getTypeArguments(Class<?> cls, final Class<?> toClass,
			final Map<TypeVariable<?>, Type> subtypeVarAssigns) {
		// make sure they're assignable
		if (!isAssignable(cls, toClass)) {
			return null;
		}

		// can't work with primitives
		if (cls.isPrimitive()) {
			// both classes are primitives?
			if (toClass.isPrimitive()) {
				// dealing with widening here. No type arguments to be
				// harvested with these two types.
				return new HashMap<>();
			}

			// work with wrapper the wrapper class instead of the primitive
			cls = ClassUtils.primitiveToWrapper(cls);
		}

		// create a copy of the incoming map, or an empty one if it's null
		final HashMap<TypeVariable<?>, Type> typeVarAssigns = subtypeVarAssigns == null ? new HashMap<TypeVariable<?>, Type>()
				: new HashMap<>(subtypeVarAssigns);

		// has target class been reached?
		if (toClass.equals(cls)) {
			return typeVarAssigns;
		}

		// walk the inheritance hierarchy until the target class is reached
		return getTypeArguments(getClosestParentType(cls, toClass), toClass, typeVarAssigns);
	}

	/**
	 * <p>Tries to determine the type arguments of a class/interface based on a
	 * super parameterized type's type arguments. This method is the inverse of
	 * {@link #getTypeArguments(Type, Class)} which gets a class/interface's
	 * type arguments based on a subtype. It is far more limited in determining
	 * the type arguments for the subject class's type variables in that it can
	 * only determine those parameters that map from the subject {@link Class}
	 * object to the supertype.</p> <p>Example: {@link java.util.TreeSet
	 * TreeSet} sets its parameter as the parameter for
	 * {@link java.util.NavigableSet NavigableSet}, which in turn sets the
	 * parameter of {@link java.util.SortedSet}, which in turn sets the
	 * parameter of {@link Set}, which in turn sets the parameter of
	 * {@link java.util.Collection}, which in turn sets the parameter of
	 * {@link java.lang.Iterable}. Since {@code TreeSet}'s parameter maps
	 * (indirectly) to {@code Iterable}'s parameter, it will be able to
	 * determine that based on the super type {@code Iterable<? extends
	 * Map<Integer, ? extends Collection<?>>>}, the parameter of
	 * {@code TreeSet} is {@code ? extends Map<Integer, ? extends
	 * Collection<?>>}.</p>
	 *
	 * @param cls the class whose type parameters are to be determined, not {@code null}
	 * @param superType the super type from which {@code cls}'s type
	 * arguments are to be determined, not {@code null}
	 * @return a {@code Map} of the type assignments that could be determined
	 * for the type variables in each type in the inheritance hierarchy from
	 * {@code type} to {@code toClass} inclusive.
	 */
	public static Map<TypeVariable<?>, Type> determineTypeArguments(final Class<?> cls,
			final ParameterizedType superType) {

		final Class<?> superClass = getRawType(superType);

		// compatibility check
		if (!isAssignable(cls, superClass)) {
			return null;
		}

		if (cls.equals(superClass)) {
			return getTypeArguments(superType, superClass, null);
		}

		// get the next class in the inheritance hierarchy
		final Type midType = getClosestParentType(cls, superClass);

		// can only be a class or a parameterized type
		if (midType instanceof Class<?>) {
			return determineTypeArguments((Class<?>) midType, superType);
		}

		final ParameterizedType midParameterizedType = (ParameterizedType) midType;
		final Class<?> midClass = getRawType(midParameterizedType);
		// get the type variables of the mid class that map to the type
		// arguments of the super class
		final Map<TypeVariable<?>, Type> typeVarAssigns = determineTypeArguments(midClass, superType);
		// map the arguments of the mid type to the class type variables
		mapTypeVariablesToArguments(cls, midParameterizedType, typeVarAssigns);

		return typeVarAssigns;
	}

	/**
	 * <p>Performs a mapping of type variables.</p>
	 *
	 * @param <T> the generic type of the class in question
	 * @param cls the class in question
	 * @param parameterizedType the parameterized type
	 * @param typeVarAssigns the map to be filled
	 */
	private static <T> void mapTypeVariablesToArguments(final Class<T> cls,
			final ParameterizedType parameterizedType, final Map<TypeVariable<?>, Type> typeVarAssigns) {
		// capture the type variables from the owner type that have assignments
		final Type ownerType = parameterizedType.getOwnerType();

		if (ownerType instanceof ParameterizedType) {
			// recursion to make sure the owner's owner type gets processed
			mapTypeVariablesToArguments(cls, (ParameterizedType) ownerType, typeVarAssigns);
		}

		// parameterizedType is a generic interface/class (or it's in the owner
		// hierarchy of said interface/class) implemented/extended by the class
		// cls. Find out which type variables of cls are type arguments of
		// parameterizedType:
		final Type[] typeArgs = parameterizedType.getActualTypeArguments();

		// of the cls's type variables that are arguments of parameterizedType,
		// find out which ones can be determined from the super type's arguments
		final TypeVariable<?>[] typeVars = getRawType(parameterizedType).getTypeParameters();

		// use List view of type parameters of cls so the contains() method can be used:
		final List<TypeVariable<Class<T>>> typeVarList = Arrays.asList(cls
				.getTypeParameters());

		for (int i = 0; i < typeArgs.length; i++) {
			final TypeVariable<?> typeVar = typeVars[i];
			final Type typeArg = typeArgs[i];

			// argument of parameterizedType is a type variable of cls
			if (typeVarList.contains(typeArg)
					// type variable of parameterizedType has an assignment in
					// the super type.
					&& typeVarAssigns.containsKey(typeVar)) {
				// map the assignment to the cls's type variable
				typeVarAssigns.put((TypeVariable<?>) typeArg, typeVarAssigns.get(typeVar));
			}
		}
	}

	/**
	 * <p>Get the closest parent type to the
	 * super class specified by {@code superClass}.</p>
	 *
	 * @param cls the class in question
	 * @param superClass the super class
	 * @return the closes parent type
	 */
	private static Type getClosestParentType(final Class<?> cls, final Class<?> superClass) {
		// only look at the interfaces if the super class is also an interface
		if (superClass.isInterface()) {
			// get the generic interfaces of the subject class
			final Type[] interfaceTypes = cls.getGenericInterfaces();
			// will hold the best generic interface match found
			Type genericInterface = null;

			// find the interface closest to the super class
			for (final Type midType : interfaceTypes) {
				Class<?> midClass = null;

				if (midType instanceof ParameterizedType) {
					midClass = getRawType((ParameterizedType) midType);
				} else if (midType instanceof Class<?>) {
					midClass = (Class<?>) midType;
				} else {
					throw new IllegalStateException("Unexpected generic"
							+ " interface type found: " + midType);
				}

				// check if this interface is further up the inheritance chain
				// than the previously found match
				if (isAssignable(midClass, superClass)
						&& isAssignable(genericInterface, (Type) midClass)) {
					genericInterface = midType;
				}
			}

			// found a match?
			if (genericInterface != null) {
				return genericInterface;
			}
		}

		// none of the interfaces were descendants of the target class, so the
		// super class has to be one, instead
		return cls.getGenericSuperclass();
	}

	/**
	 * <p>Checks if the given value can be assigned to the target type
	 * following the Java generics rules.</p>
	 *
	 * @param value the value to be checked
	 * @param type the target type
	 * @return {@code true} if {@code value} is an instance of {@code type}.
	 */
	public static boolean isInstance(final Object value, final Type type) {
		if (type == null) {
			return false;
		}

		return value == null ? !(type instanceof Class<?>) || !((Class<?>) type).isPrimitive()
				: isAssignable(value.getClass(), type, null);
	}

	/**
	 * <p>This method strips out the redundant upper bound types in type
	 * variable types and wildcard types (or it would with wildcard types if
	 * multiple upper bounds were allowed).</p> <p>Example, with the variable
	 * type declaration:
	 *
	 * <pre>&lt;K extends java.util.Collection&lt;String&gt; &amp;
	 * java.util.List&lt;String&gt;&gt;</pre>
	 *
	 * <p>
	 * since {@code List} is a subinterface of {@code Collection},
	 * this method will return the bounds as if the declaration had been:
	 * </p>
	 *
	 * <pre>&lt;K extends java.util.List&lt;String&gt;&gt;</pre>
	 *
	 * @param bounds an array of types representing the upper bounds of either
	 * {@link WildcardType} or {@link TypeVariable}, not {@code null}.
	 * @return an array containing the values from {@code bounds} minus the
	 * redundant types.
	 */
	public static Type[] normalizeUpperBounds(final Type[] bounds) {
		// don't bother if there's only one (or none) type
		if (bounds.length < 2) {
			return bounds;
		}

		final Set<Type> types = new HashSet<>(bounds.length);

		for (final Type type1 : bounds) {
			boolean subtypeFound = false;

			for (final Type type2 : bounds) {
				if (type1 != type2 && isAssignable(type2, type1, null)) {
					subtypeFound = true;
					break;
				}
			}

			if (!subtypeFound) {
				types.add(type1);
			}
		}

		return types.toArray(new Type[types.size()]);
	}

	/**
	 * <p>Returns an array containing the sole type of {@link Object} if
	 * {@link TypeVariable#getBounds()} returns an empty array. Otherwise, it
	 * returns the result of {@link TypeVariable#getBounds()} passed into
	 * {@link #normalizeUpperBounds}.</p>
	 *
	 * @param typeVariable the subject type variable, not {@code null}
	 * @return a non-empty array containing the bounds of the type variable.
	 */
	public static Type[] getImplicitBounds(final TypeVariable<?> typeVariable) {
		final Type[] bounds = typeVariable.getBounds();

		return bounds.length == 0 ? new Type[] { Object.class } : normalizeUpperBounds(bounds);
	}

	/**
	 * <p>Returns an array containing the sole value of {@link Object} if
	 * {@link WildcardType#getUpperBounds()} returns an empty array. Otherwise,
	 * it returns the result of {@link WildcardType#getUpperBounds()}
	 * passed into {@link #normalizeUpperBounds}.</p>
	 *
	 * @param wildcardType the subject wildcard type, not {@code null}
	 * @return a non-empty array containing the upper bounds of the wildcard
	 * type.
	 */
	public static Type[] getImplicitUpperBounds(final WildcardType wildcardType) {
		final Type[] bounds = wildcardType.getUpperBounds();

		return bounds.length == 0 ? new Type[] { Object.class } : normalizeUpperBounds(bounds);
	}

	/**
	 * <p>Returns an array containing a single value of {@code null} if
	 * {@link WildcardType#getLowerBounds()} returns an empty array. Otherwise,
	 * it returns the result of {@link WildcardType#getLowerBounds()}.</p>
	 *
	 * @param wildcardType the subject wildcard type, not {@code null}
	 * @return a non-empty array containing the lower bounds of the wildcard
	 * type.
	 */
	public static Type[] getImplicitLowerBounds(final WildcardType wildcardType) {
		final Type[] bounds = wildcardType.getLowerBounds();

		return bounds.length == 0 ? new Type[] { null } : bounds;
	}

	/**
	 * <p>Determines whether or not specified types satisfy the bounds of their
	 * mapped type variables. When a type parameter extends another (such as
	 * {@code <T, S extends T>}), uses another as a type parameter (such as
	 * {@code <T, S extends Comparable>>}), or otherwise depends on
	 * another type variable to be specified, the dependencies must be included
	 * in {@code typeVarAssigns}.</p>
	 *
	 * @param typeVarAssigns specifies the potential types to be assigned to the
	 * type variables, not {@code null}.
	 * @return whether or not the types can be assigned to their respective type
	 * variables.
	 */
	public static boolean typesSatisfyVariables(final Map<TypeVariable<?>, Type> typeVarAssigns) {
		// all types must be assignable to all the bounds of the their mapped
		// type variable.
		for (final Map.Entry<TypeVariable<?>, Type> entry : typeVarAssigns.entrySet()) {
			final TypeVariable<?> typeVar = entry.getKey();
			final Type type = entry.getValue();

			for (final Type bound : getImplicitBounds(typeVar)) {
				if (!isAssignable(type, substituteTypeVariables(bound, typeVarAssigns),
						typeVarAssigns)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * <p>Transforms the passed in type to a {@link Class} object. Type-checking method of convenience.</p>
	 *
	 * @param parameterizedType the type to be converted
	 * @return the corresponding {@code Class} object
	 * @throws IllegalStateException if the conversion fails
	 */
	private static Class<?> getRawType(final ParameterizedType parameterizedType) {
		final Type rawType = parameterizedType.getRawType();

		// check if raw type is a Class object
		// not currently necessary, but since the return type is Type instead of
		// Class, there's enough reason to believe that future versions of Java
		// may return other Type implementations. And type-safety checking is
		// rarely a bad idea.
		if (!(rawType instanceof Class<?>)) {
			throw new IllegalStateException("Wait... What!? Type of rawType: " + rawType);
		}

		return (Class<?>) rawType;
	}

	/**
	 * <p>Get the raw type of a Java type, given its context. Primarily for use
	 * with {@link TypeVariable}s and {@link GenericArrayType}s, or when you do
	 * not know the runtime type of {@code type}: if you know you have a
	 * {@link Class} instance, it is already raw; if you know you have a
	 * {@link ParameterizedType}, its raw type is only a method call away.</p>
	 *
	 * @param type to resolve
	 * @param assigningType type to be resolved against
	 * @return the resolved {@link Class} object or {@code null} if
	 * the type could not be resolved
	 */
	public static Class<?> getRawType(final Type type, final Type assigningType) {
		if (type instanceof Class<?>) {
			// it is raw, no problem
			return (Class<?>) type;
		}

		if (type instanceof ParameterizedType) {
			// simple enough to get the raw type of a ParameterizedType
			return getRawType((ParameterizedType) type);
		}

		if (type instanceof TypeVariable<?>) {
			if (assigningType == null) {
				return null;
			}

			// get the entity declaring this type variable
			final Object genericDeclaration = ((TypeVariable<?>) type).getGenericDeclaration();

			// can't get the raw type of a method- or constructor-declared type
			// variable
			if (!(genericDeclaration instanceof Class<?>)) {
				return null;
			}

			// get the type arguments for the declaring class/interface based
			// on the enclosing type
			final Map<TypeVariable<?>, Type> typeVarAssigns = getTypeArguments(assigningType,
					(Class<?>) genericDeclaration);

			// enclosingType has to be a subclass (or subinterface) of the
			// declaring type
			if (typeVarAssigns == null) {
				return null;
			}

			// get the argument assigned to this type variable
			final Type typeArgument = typeVarAssigns.get(type);

			if (typeArgument == null) {
				return null;
			}

			// get the argument for this type variable
			return getRawType(typeArgument, assigningType);
		}

		if (type instanceof GenericArrayType) {
			// get raw component type
			final Class<?> rawComponentType = getRawType(((GenericArrayType) type)
					.getGenericComponentType(), assigningType);

			// create array type from raw component type and return its class
			return Array.newInstance(rawComponentType, 0).getClass();
		}

		// (hand-waving) this is not the method you're looking for
		if (type instanceof WildcardType) {
			return null;
		}

		throw new IllegalArgumentException("unknown type: " + type);
	}

	/**
	 * Learn whether the specified type denotes an array type.
	 * @param type the type to be checked
	 * @return {@code true} if {@code type} is an array class or a {@link GenericArrayType}.
	 */
	public static boolean isArrayType(final Type type) {
		return type instanceof GenericArrayType || type instanceof Class<?> && ((Class<?>) type).isArray();
	}

	/**
	 * Get the array component type of {@code type}.
	 * @param type the type to be checked
	 * @return component type or null if type is not an array type
	 */
	public static Type getArrayComponentType(final Type type) {
		if (type instanceof Class<?>) {
			final Class<?> clazz = (Class<?>) type;
			return clazz.isArray() ? clazz.getComponentType() : null;
		}
		if (type instanceof GenericArrayType) {
			return ((GenericArrayType) type).getGenericComponentType();
		}
		return null;
	}



	/**
	 * Check equality of types.
	 *
	 * @param t1 the first type
	 * @param t2 the second type
	 * @return boolean
	 * @since 3.2
	 */
	public static boolean equals(final Type t1, final Type t2) {
		if (Objects.equals(t1, t2)) {
			return true;
		}
		if (t1 instanceof ParameterizedType) {
			return equals((ParameterizedType) t1, t2);
		}
		if (t1 instanceof GenericArrayType) {
			return equals((GenericArrayType) t1, t2);
		}
		if (t1 instanceof WildcardType) {
			return equals((WildcardType) t1, t2);
		}
		return false;
	}

	/**
	 * Learn whether {@code t} equals {@code p}.
	 * @param p LHS
	 * @param t RHS
	 * @return boolean
	 * @since 3.2
	 */
	private static boolean equals(final ParameterizedType p, final Type t) {
		if (t instanceof ParameterizedType) {
			final ParameterizedType other = (ParameterizedType) t;
			if (equals(p.getRawType(), other.getRawType()) && equals(p.getOwnerType(), other.getOwnerType())) {
				return equals(p.getActualTypeArguments(), other.getActualTypeArguments());
			}
		}
		return false;
	}

	/**
	 * Learn whether {@code t} equals {@code a}.
	 * @param a LHS
	 * @param t RHS
	 * @return boolean
	 * @since 3.2
	 */
	private static boolean equals(final GenericArrayType a, final Type t) {
		return t instanceof GenericArrayType
				&& equals(a.getGenericComponentType(), ((GenericArrayType) t).getGenericComponentType());
	}

	/**
	 * Learn whether {@code t} equals {@code w}.
	 * @param w LHS
	 * @param t RHS
	 * @return boolean
	 * @since 3.2
	 */
	private static boolean equals(final WildcardType w, final Type t) {
		if (t instanceof WildcardType) {
			final WildcardType other = (WildcardType) t;
			return equals(getImplicitLowerBounds(w), getImplicitLowerBounds(other))
					&& equals(getImplicitUpperBounds(w), getImplicitUpperBounds(other));
		}
		return false;
	}

	/**
	 * Learn whether {@code t1} equals {@code t2}.
	 * @param t1 LHS
	 * @param t2 RHS
	 * @return boolean
	 * @since 3.2
	 */
	private static boolean equals(final Type[] t1, final Type[] t2) {
		if (t1.length == t2.length) {
			for (int i = 0; i < t1.length; i++) {
				if (!equals(t1[i], t2[i])) {
					return false;
				}
			}
			return true;
		}
		return false;
	}




}