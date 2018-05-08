/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.gen;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MultimapBuilder;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.Annotation;
import com.google.devtools.j2objc.ast.BodyDeclaration;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.FieldDeclaration;
import com.google.devtools.j2objc.ast.FunctionDeclaration;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.Name;
import com.google.devtools.j2objc.ast.NativeDeclaration;
import com.google.devtools.j2objc.ast.PropertyAnnotation;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.util.ElementUtil;
import com.google.devtools.j2objc.util.ErrorUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.devtools.j2objc.util.TranslationUtil;
import com.google.devtools.j2objc.util.TypeUtil;
import com.google.devtools.j2objc.util.UnicodeUtils;
import com.google.j2objc.annotations.Property;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;

/**
 * Base class for generating type declarations, either public or private.
 *
 * @author Tom Ball, Keith Stanger
 */
public class TypeDeclarationGenerator extends TypeGenerator {

  private static final String DEPRECATED_ATTRIBUTE = "__attribute__((deprecated))";

  protected TypeDeclarationGenerator(SourceBuilder builder, AbstractTypeDeclaration node) {
    super(builder, node);
  }

  public static void generate(SourceBuilder builder, AbstractTypeDeclaration node) {
    new TypeDeclarationGenerator(builder, node).generate();
  }

  protected boolean printPrivateDeclarations() {
    return false;
  }

  @Override
  protected boolean shouldPrintDeclaration(BodyDeclaration decl) {
    if (decl instanceof MethodDeclaration && !((MethodDeclaration) decl).hasDeclaration()) {
      return false;
    }
    return decl.hasPrivateDeclaration() == printPrivateDeclarations();
  }

  private void generate() {
    // If the type is private, then generate nothing in the header. The initial
    // declaration will go in the implementation file instead.
    if (!typeNode.hasPrivateDeclaration()) {
      generateInitialDeclaration();
    }
  }

  // Trevor - this prints the header declaration (and maybe more)
  protected void generateInitialDeclaration() {
    if (typeNode.isDeadClass()) {
      printStaticFieldDeclarations();
      return;
    }
    printNativeEnum();

    printTypeDocumentation();
    if (typeElement.getKind().isInterface()) {
      printf("@protocol %s", typeName);
    } else {
      // Module start
      printf("module %s : sig\n", typeName); // TODO(trevor) helper for module names
      indent();
    }

    // After module, print the class body
    printIndent();
    printf("class %s : object\n", javaClassToOCamlClassName(typeElement));
    indent();
    printImplementedProtocols();
    if (!typeElement.getKind().isInterface()) {
      printInstanceVariables();
    }

    printProperties();
    if (!typeElement.getKind().isInterface()) {
      printStaticAccessors();
    }
     printInnerDeclarations();

    if (ElementUtil.isPackageInfo(typeElement)) {
      printOuterDeclarations();
      return;
    }

    // end of class
    unindent();
    printIndent();
    println("end");

    printConstructors();
    printCompanionClassDeclaration();
    printEnumConstants();
    printFieldSetters();
    printStaticFieldDeclarations();
    printOuterDeclarations();
    printBoxedOperators();

    // End module
    unindent();
    println("end");

    printUnprefixedAlias();
  }

  private void printNativeEnum() {
    if (!(typeNode instanceof EnumDeclaration)) {
      return;
    }

    List<EnumConstantDeclaration> constants = ((EnumDeclaration) typeNode).getEnumConstants();
    String nativeName = NameTable.getNativeEnumName(typeName);

    // C doesn't allow empty enum declarations.  Java does, so we skip the
    // C enum declaration and generate the type declaration.
    if (!constants.isEmpty()) {
      newline();
      printf("typedef NS_ENUM(NSUInteger, %s) {\n", nativeName);

      // Print C enum typedef.
      indent();
      int ordinal = 0;
      for (EnumConstantDeclaration constant : constants) {
        printIndent();
        printf("%s_%s = %d,\n",
            nativeName, nameTable.getVariableBaseName(constant.getVariableElement()), ordinal++);
      }
      unindent();
      print("};\n");
    }
  }

  private void printTypeDocumentation() {
    newline();
    JavadocGenerator.printDocComment(getBuilder(), typeNode.getJavadoc());
    if (needsDeprecatedAttribute(typeNode.getAnnotations())) {
      println(DEPRECATED_ATTRIBUTE);
    }
  }

  private String getSuperTypeName() {
    TypeElement supertype = TranslationUtil.getSuperType(typeNode);
    if (supertype != null) {
      return nameTable.getFullName(supertype);
    }
    return "NSObject";
  }

  private List<String> getInterfaceNames() {
    if (ElementUtil.isAnnotationType(typeElement)) {
      return Lists.newArrayList("JavaLangAnnotationAnnotation");
    }
    List<String> names = Lists.newArrayList();
    for (TypeElement intrface : TranslationUtil.getInterfaceTypes(typeNode)) {
      names.add(nameTable.getFullName(intrface));
    }
    if (ElementUtil.getQualifiedName(typeElement).equals("java.lang.Enum")) {
      names.remove("NSCopying");
      names.add(0, "NSCopying");
    } else if (isInterfaceType()) {
      names.add("JavaObject");
    }
    return names;
  }

  private void printImplementedProtocols() {
    List<String> interfaces = getInterfaceNames();
    if (!interfaces.isEmpty()) {
      print(" < ");
      boolean isFirst = true;
      for (String name : interfaces) {
        if (!isFirst) {
          print(", ");
        }
        isFirst = false;
        print(name);
      }
      print(" >");
    }
  }

  protected void printStaticInterfaceMethods() {
    for (BodyDeclaration declaration : getInnerDeclarations()) {
      if (declaration.getKind().equals(TreeNode.Kind.METHOD_DECLARATION)) {
        printMethodDeclaration((MethodDeclaration) declaration, true);
      }
    }
  }

  /**
   * Prints the list of static variable and/or enum constant accessor methods.
   */
  protected void printStaticAccessors() {
    if (options.staticAccessorMethods()) {
      for (VariableDeclarationFragment fragment : getStaticFields()) {
        VariableElement var = fragment.getVariableElement();
        String accessorName = nameTable.getStaticAccessorName(var);
        String objcType = nameTable.getObjCType(var.asType());
        printf("\n+ (%s)%s;\n", objcType, accessorName);
        if (!ElementUtil.isFinal(var)) {
          printf("\n+ (void)set%s:(%s)value;\n", NameTable.capitalize(accessorName), objcType);
        }
      }
      if (typeNode instanceof EnumDeclaration) {
        for (EnumConstantDeclaration constant : ((EnumDeclaration) typeNode).getEnumConstants()) {
          String accessorName = nameTable.getStaticAccessorName(constant.getVariableElement());
          if (options.nullability()) {
            printf("\n+ (%s * __nonnull)%s;\n", typeName, accessorName);
          } else {
            printf("\n+ (%s *)%s;\n", typeName, accessorName);
          }
        }
      }
    }
  }

  /**
   * Prints the list of instance variables in a type.
   */
  protected void printInstanceVariables() {
    Iterable<VariableDeclarationFragment> fields = getInstanceFields();
    if (Iterables.isEmpty(fields)) {
      return;
    }

    FieldDeclaration lastDeclaration = null;
    boolean needsAsterisk = false;
    for (VariableDeclarationFragment fragment : fields) {
      VariableElement varElement = fragment.getVariableElement();
      FieldDeclaration declaration = (FieldDeclaration) fragment.getParent();
      String ocamlType = getDeclarationType(varElement);

      if (declaration != lastDeclaration) {
        if (lastDeclaration != null) {
          println(";");
        }
        lastDeclaration = declaration;
        JavadocGenerator.printDocComment(getBuilder(), declaration.getJavadoc());
        printIndent();
        if (ElementUtil.isWeakReference(varElement) && !ElementUtil.isVolatile(varElement)) {
          // We must add this even without -use-arc because the header may be
          // included by a file compiled with ARC.
          print("__unsafe_unretained ");
        }
        needsAsterisk = ocamlType.endsWith("*");
        if (needsAsterisk) {
          // Strip pointer from type, as it will be added when appending fragment.
          // This is necessary to create "Foo *one, *two;" declarations.
          ocamlType = ocamlType.substring(0, ocamlType.length() - 2);
        }
      } else {
        print(", ");
      }
      if (needsAsterisk) {
        print('*');
      }
      // TODO helper for val printing
      printf("val %s : %s", nameTable.getVariableBaseName(varElement),
              ocamlType
              );
    }
    println(""); // end of declaration
  }

  /**
   * Locate method which matches either Java or Objective C getter name patterns.
   */
  public static ExecutableElement findGetterMethod(
      String propertyName, TypeMirror propertyType, TypeElement declaringClass) {
    // Try Objective-C getter naming convention.
    ExecutableElement getter = ElementUtil.findMethod(declaringClass, propertyName);
    if (getter == null) {
      // Try Java getter naming conventions.
      String prefix = TypeUtil.isBoolean(propertyType) ? "is" : "get";
      getter = ElementUtil.findMethod(declaringClass, prefix + NameTable.capitalize(propertyName));
    }
    return getter;
  }

  /**
   * Locate method which matches the Java/Objective C setter name pattern.
   */
  public static ExecutableElement findSetterMethod(
      String propertyName, TypeMirror type, TypeElement declaringClass) {
    return ElementUtil.findMethod(declaringClass, "set" + NameTable.capitalize(propertyName),
        TypeUtil.getQualifiedName(type));
  }

  protected void printProperties() {
    Iterable<VariableDeclarationFragment> fields = getAllFields();
    for (VariableDeclarationFragment fragment : fields) {
      FieldDeclaration fieldDecl = (FieldDeclaration) fragment.getParent();
      VariableElement varElement = fragment.getVariableElement();
      PropertyAnnotation property = (PropertyAnnotation)
          TreeUtil.getAnnotation(Property.class, fieldDecl.getAnnotations());
      if (property != null) {
        print("@property ");
        TypeMirror varType = varElement.asType();
        String propertyName = nameTable.getVariableBaseName(varElement);

        // Add default getter/setter here, as each fragment needs its own attributes
        // to support its unique accessors.
        Set<String> attributes = property.getPropertyAttributes();
        TypeElement declaringClass = ElementUtil.getDeclaringClass(varElement);
        ExecutableElement getter = findGetterMethod(propertyName, varType, declaringClass);
        if (getter != null) {
          // Update getter from its Java name to its selector. This is normally the
          // same since getters have no parameters, but the name may be reserved.
          attributes.remove("getter=" + property.getGetter());
          attributes.add("getter=" + nameTable.getMethodSelector(getter));
          if (!ElementUtil.isSynchronized(getter)) {
            attributes.add("nonatomic");
          }
        }
        ExecutableElement setter = findSetterMethod(propertyName, varType, declaringClass);
        if (setter != null) {
          // Update setter from its Java name to its selector.
          attributes.remove("setter=" + property.getSetter());
          attributes.add("setter=" + nameTable.getMethodSelector(setter));
          if (!ElementUtil.isSynchronized(setter)) {
            attributes.add("nonatomic");
          }
        }

        if (ElementUtil.isStatic(varElement)) {
          attributes.add("class");
        } else if (attributes.contains("class")) {
          ErrorUtil.error(fragment, "Only static fields can be translated to class properties");
        }
        if (attributes.contains("class") && !options.staticAccessorMethods()) {
          // Class property accessors must be present, as they are not synthesized by runtime.
          ErrorUtil.error(fragment, "Class properties require either a --swift-friendly or"
              + " --static-accessor-methods flag");
        }

        if (options.nullability() && !varElement.asType().getKind().isPrimitive()) {
          if (ElementUtil.hasNullableAnnotation(varElement)) {
            attributes.add("nullable");
          } else if (ElementUtil.isNonnull(varElement, parametersNonnullByDefault)) {
            attributes.add("nonnull");
          } else if (!attributes.contains("null_unspecified")) {
            attributes.add("null_resettable");
          }
        }

        if (!attributes.isEmpty()) {
          print('(');
          print(PropertyAnnotation.toAttributeString(attributes));
          print(") ");
        }

        String objcType = nameTable.getObjCType(varType);
        print(objcType);
        if (!objcType.endsWith("*")) {
          print(' ');
        }
        println(propertyName + ";");
      }
    }
  }

  protected void printCompanionClassDeclaration() {
    if (!typeElement.getKind().isInterface() || !needsCompanionClass()
        || printPrivateDeclarations() == needsPublicCompanionClass()) {
      return;
    }
    printf("\n@interface %s : NSObject", typeName);
    if (ElementUtil.isRuntimeAnnotation(typeElement)) {
      // Print annotation implementation interface.
      printf(" < %s >", typeName);
    }
    printInstanceVariables();
    printStaticInterfaceMethods();
    printStaticAccessors();
    println("\n@end");
  }

  private void printStaticInitFunction() {
    if (hasInitializeMethod()) {
      printf("\nJ2OBJC_STATIC_INIT(%s)\n", typeName);
    } else {
      printf("\nJ2OBJC_EMPTY_STATIC_INIT(%s)\n", typeName);
    }
  }

  private void printEnumConstants() {
    if (typeNode instanceof EnumDeclaration) {
      newline();
      println("/*! INTERNAL ONLY - Use enum accessors declared below. */");
      printf("FOUNDATION_EXPORT %s *%s_values_[];\n", typeName, typeName);
      for (EnumConstantDeclaration constant : ((EnumDeclaration) typeNode).getEnumConstants()) {
        String varName = nameTable.getVariableBaseName(constant.getVariableElement());
        newline();
        JavadocGenerator.printDocComment(getBuilder(), constant.getJavadoc());
        printf("inline %s *%s_get_%s(void);\n", typeName, typeName, varName);
        printf("J2OBJC_ENUM_CONSTANT(%s, %s)\n", typeName, varName);
      }
    }
  }

  private static final Predicate<VariableDeclarationFragment> NEEDS_SETTER =
      new Predicate<VariableDeclarationFragment>() {
    @Override
    public boolean apply(VariableDeclarationFragment fragment) {
      VariableElement var = fragment.getVariableElement();
      if (ElementUtil.isRetainedWithField(var)) {
        assert !ElementUtil.isPublic(var) : "@RetainedWith fields cannot be public.";
        return false;
      }
      return !var.asType().getKind().isPrimitive() && !ElementUtil.isSynthetic(var)
          && !ElementUtil.isWeakReference(var);
    }
  };

  protected void printFieldSetters() {
    Iterable<VariableDeclarationFragment> fields =
        Iterables.filter(getInstanceFields(), NEEDS_SETTER);
    if (Iterables.isEmpty(fields)) {
      return;
    }
    for (VariableDeclarationFragment fragment : fields) {
      VariableElement var = fragment.getVariableElement();
      String typeStr = nameTable.getObjCType(var.asType());
      if (typeStr.contains(",")) {
        typeStr = "J2OBJC_ARG(" + typeStr + ')';
      }
      String fieldName = nameTable.getVariableShortName(var);
      String isVolatile = ElementUtil.isVolatile(var) ? "_VOLATILE" : "";
      println(UnicodeUtils.format("J2OBJC%s_FIELD_SETTER(%s, %s, %s)",
          isVolatile, typeName, fieldName, typeStr));
    }
  }

  protected void printStaticFieldDeclarations() {
    Iterable<VariableDeclarationFragment> fields = getStaticFields();
    if (fields.iterator().hasNext()) {
      printIndent();
      println("(* Static fields *)");
    }
    for (VariableDeclarationFragment fragment : fields) {
      if (typeNode.isDeadClass()) {
        printDeadClassConstant(fragment);
      } else {
        printStaticFieldFullDeclaration(fragment);
      }
    }
  }

  // Overridden in TypePrivateDeclarationGenerator
  protected void printStaticFieldDeclaration(
      VariableDeclarationFragment fragment, String baseDeclaration) {
    println("/*! INTERNAL ONLY - Use accessor function from above. */");
    println("FOUNDATION_EXPORT " + baseDeclaration + ";");
  }

  private void printStaticFieldFullDeclarationOCaml(VariableDeclarationFragment fragment) {
    VariableElement var = fragment.getVariableElement();
    String ocamlType = nameTable.getOCamlType(var.asType());
    String name = nameTable.getVariableShortName(var);
    printIndent();
    printf("val %s : %s\n", name, ocamlType);
    return;
  }

  private void printStaticFieldFullDeclaration(VariableDeclarationFragment fragment) {
    printStaticFieldFullDeclarationOCaml(fragment);
    return;
//    VariableElement var = fragment.getVariableElement();
//    boolean isVolatile = ElementUtil.isVolatile(var);
//    String objcType = nameTable.getObjCType(var.asType());
//    String objcTypePadded = objcType + (objcType.endsWith("*") ? "" : " ");
//    String declType = getDeclarationType(var);
//    declType += (declType.endsWith("*") ? "" : " ");
//    String name = nameTable.getVariableShortName(var);
//    boolean isFinal = ElementUtil.isFinal(var);
//    boolean isPrimitive = var.asType().getKind().isPrimitive();
//    boolean isConstant = ElementUtil.isPrimitiveConstant(var);
//    String qualifiers = isConstant ? "_CONSTANT"
//        : (isPrimitive ? "_PRIMITIVE" : "_OBJ") + (isVolatile ? "_VOLATILE" : "")
//        + (isFinal ? "_FINAL" : "");
//    newline();
//    FieldDeclaration decl = (FieldDeclaration) fragment.getParent();
//    JavadocGenerator.printDocComment(getBuilder(), decl.getJavadoc());
//    printf("inline %s%s_get_%s(void);\n", objcTypePadded, typeName, name);
//    if (!isFinal) {
//      printf("inline %s%s_set_%s(%svalue);\n", objcTypePadded, typeName, name, objcTypePadded);
//      if (isPrimitive && !isVolatile) {
//        printf("inline %s *%s_getRef_%s(void);\n", objcType, typeName, name);
//      }
//    }
//    if (isConstant) {
//      Object value = var.getConstantValue();
//      assert value != null;
//      printf("#define %s_%s %s\n", typeName, name, LiteralGenerator.generate(value));
//    } else {
//      printStaticFieldDeclaration(
//          fragment, UnicodeUtils.format("%s%s_%s", declType, typeName, name));
//    }
//    printf("J2OBJC_STATIC_FIELD%s(%s, %s, %s)\n", qualifiers, typeName, name, objcType);
  }

  // Overridden in TypePrivateDeclarationGenerator
  protected void printDeadClassConstant(VariableDeclarationFragment fragment) {
    VariableElement var = fragment.getVariableElement();
    Object value = var.getConstantValue();
    assert value != null;
    String declType = getDeclarationType(var);
    declType += (declType.endsWith("*") ? "" : " ");
    String name = nameTable.getVariableShortName(var);
    if (ElementUtil.isPrimitiveConstant(var)) {
      printf("#define %s_%s %s\n", typeName, name, LiteralGenerator.generate(value));
    } else {
      println("FOUNDATION_EXPORT "
          + UnicodeUtils.format("%s%s_%s", declType, typeName, name) + ";");
    }
  }

  private void printTypeLiteralDeclaration() {
    if (needsTypeLiteral()) {
      newline();
      printf("J2OBJC_TYPE_LITERAL_HEADER(%s)\n", typeName);
    }
  }

  private void printBoxedOperators() {
    PrimitiveType primitiveType = env.typeUtil().unboxedType(typeElement.asType());
    if (primitiveType == null) {
      return;
    }
    char binaryName = env.typeUtil().getSignatureName(primitiveType).charAt(0);
    String primitiveName = TypeUtil.getName(primitiveType);
    String capName = NameTable.capitalize(primitiveName);
    String primitiveTypeName = NameTable.getPrimitiveObjCType(primitiveType);
    String valueMethod = primitiveName + "Value";
    if (primitiveName.equals("long")) {
      valueMethod = "longLongValue";
    } else if (primitiveName.equals("byte")) {
      valueMethod = "charValue";
    }
    newline();
    printf("BOXED_INC_AND_DEC(%s, %s, %s)\n", capName, valueMethod, typeName);

    if ("DFIJ".indexOf(binaryName) >= 0) {
      printf("BOXED_COMPOUND_ASSIGN_ARITHMETIC(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
    if ("IJ".indexOf(binaryName) >= 0) {
      printf("BOXED_COMPOUND_ASSIGN_MOD(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
    if ("DF".indexOf(binaryName) >= 0) {
      printf("BOXED_COMPOUND_ASSIGN_FPMOD(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
    if ("IJ".indexOf(binaryName) >= 0) {
      printf("BOXED_COMPOUND_ASSIGN_BITWISE(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
    if ("I".indexOf(binaryName) >= 0) {
      printf("BOXED_SHIFT_ASSIGN_32(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
    if ("J".indexOf(binaryName) >= 0) {
      printf("BOXED_SHIFT_ASSIGN_64(%s, %s, %s, %s)\n",
          capName, valueMethod, primitiveTypeName, typeName);
    }
  }

  private void printUnprefixedAlias() {
    if (ElementUtil.isTopLevel(typeElement)) {
      String unprefixedName =
          NameTable.camelCaseQualifiedName(ElementUtil.getQualifiedName(typeElement));
      if (!unprefixedName.equals(typeName)) {
        if (typeElement.getKind().isInterface()) {
          // Protocols can't be used in typedefs.
          printf("\n#define %s %s\n", unprefixedName, typeName);
        } else {
          printf("\n@compatibility_alias %s %s;\n", unprefixedName, typeName);
        }
      }
    }
  }

  /**
   * Emit method declaration.
   *
   * @param m The method.
   * @param isCompanionClass If true, emit only if m is a static interface method.
   */
  private void printMethodDeclaration(MethodDeclaration m, boolean isCompanionClass) {
    ExecutableElement methodElement = m.getExecutableElement();
    TypeElement typeElement = ElementUtil.getDeclaringClass(methodElement);

    if (typeElement.getKind().isInterface()) {
      // isCompanion and isStatic must be both false (i.e. this prints a non-static method decl
      // in @protocol) or must both be true (i.e. this prints a static method decl in the
      // companion class' @interface).
      if (isCompanionClass != ElementUtil.isStatic(methodElement)) {
        return;
      }
    }

    JavadocGenerator.printDocComment(getBuilder(), m.getJavadoc());
    print(getMethodSignature(m));
    String methodName = nameTable.getMethodSelector(methodElement);
    if (!m.isConstructor() && NameTable.needsObjcMethodFamilyNoneAttribute(methodName)) {
      // Getting around a clang warning.
      // clang assumes that methods with names starting with new, alloc or copy
      // return objects of the same type as the receiving class, regardless of
      // the actual declared return type. This attribute tells clang to not do
      // that, please.
      // See http://clang.llvm.org/docs/AutomaticReferenceCounting.html
      // Sections 5.1 (Explicit method family control)
      // and 5.2.2 (Related result types)
      print(" OBJC_METHOD_FAMILY_NONE");
    }

    if (needsDeprecatedAttribute(m.getAnnotations())) {
      print(" " + DEPRECATED_ATTRIBUTE);
    }
    if (m.isUnavailable()) {
      print(" NS_UNAVAILABLE");
    }
    newline();
  }

  @Override
  protected void printMethodDeclaration(MethodDeclaration m) {
    printMethodDeclaration(m, false);
  }

  private boolean needsDeprecatedAttribute(List<Annotation> annotations) {
    return options.generateDeprecatedDeclarations() && hasDeprecated(annotations);
  }

  private boolean hasDeprecated(List<Annotation> annotations) {
    for (Annotation annotation : annotations) {
      Name annotationTypeName = annotation.getTypeName();
      String expectedTypeName =
          annotationTypeName.isQualifiedName() ? "java.lang.Deprecated" : "Deprecated";
      if (expectedTypeName.equals(annotationTypeName.getFullyQualifiedName())) {
        return true;
      }
    }

    return false;
  }

  @Override
  protected void printNativeDeclaration(NativeDeclaration declaration) {
    String code = declaration.getHeaderCode();
    if (code != null) {
      newline();
      print(declaration.getHeaderCode());
    }
  }

  @Override
  protected void printFunctionDeclaration(FunctionDeclaration function) {
    print("\nFOUNDATION_EXPORT " + getFunctionSignature(function, true));
    if (function.returnsRetained()) {
      print(" NS_RETURNS_RETAINED");
    }
    println(";");
  }

  /**
   * Defines the categories for grouping declarations in the header. The categories will be emitted
   * in the header in the same order that they are declared here.
   */
  private enum DeclarationCategory {
    PUBLIC("#pragma mark Public"),
    PROTECTED("#pragma mark Protected"),
    PACKAGE_PRIVATE("#pragma mark Package-Private"),
    PRIVATE("#pragma mark Private"),
    UNAVAILABLE("// Disallowed inherited constructors, do not use.");

    private final String header;

    DeclarationCategory(String header) {
      this.header = header;
    }

    private static DeclarationCategory categorize(BodyDeclaration decl) {
      if (decl instanceof MethodDeclaration && ((MethodDeclaration) decl).isUnavailable()) {
        return UNAVAILABLE;
      }
      int mods = decl.getModifiers();
      if ((mods & Modifier.PUBLIC) > 0) {
        return PUBLIC;
      } else if ((mods & Modifier.PROTECTED) > 0) {
        return PROTECTED;
      } else if ((mods & Modifier.PRIVATE) > 0) {
        return PRIVATE;
      }
      return PACKAGE_PRIVATE;
    }
  }

  /**
   * Print constructors using the <init> methods
   * OCaml constructors are functions on the module, as OCaml classes do not have static methods.
   */
  private void printConstructors() {
    // Everything is public in interfaces.
    if (isInterfaceType() || typeNode.hasPrivateDeclaration()) {
      super.printInnerDeclarations();
      return;
    }
    List<MethodDeclaration> methods = Lists.newArrayList();

    ListMultimap<DeclarationCategory, MethodDeclaration> categorizedDecls =
            MultimapBuilder.hashKeys().arrayListValues().build();
    for (BodyDeclaration innerDecl : getInnerDeclarations()) {
      if (innerDecl instanceof MethodDeclaration) {
        MethodDeclaration m = (MethodDeclaration) innerDecl;
        methods.add(m);
        ExecutableElement methodElement = m.getExecutableElement();
        TypeElement typeElement = ElementUtil.getDeclaringClass(methodElement);
        if (methodElement.getSimpleName().contentEquals("<init>")) {
          categorizedDecls.put(DeclarationCategory.categorize(innerDecl), m);
        }
      }
    }
    // Emit the categorized declarations using the declaration order of the category values.
    // TODO trevor - right now I am keeping this ordered printing to print the constructors
    // in private, public groupings. I am not sure yet if this is only called on the interface
    // though, in which case we will only print public methods. If that is the case this whole
    // method can be drastically shortened.
    for (DeclarationCategory category : DeclarationCategory.values()) {
      List<MethodDeclaration> declarations = categorizedDecls.get(category);
      if (declarations.isEmpty()) {
        continue;
      }
      Collections.sort(declarations, METHOD_DECL_ORDER);

      for (MethodDeclaration m : declarations) {
        ExecutableElement methodElement = m.getExecutableElement();
        TypeElement typeElement = ElementUtil.getDeclaringClass(methodElement);
        if (methodElement.getSimpleName().contentEquals("<init>")) {
          printIndent();
          printMethodDeclaration(m);
        }
      }
    }
  }

  /**
   * Print method declarations with #pragma mark lines documenting their scope.
   */
  @Override
  protected void printInnerDeclarations() {
    // Everything is public in interfaces.
    if (isInterfaceType() || typeNode.hasPrivateDeclaration()) {
      super.printInnerDeclarations();
      return;
    }

    ListMultimap<DeclarationCategory, BodyDeclaration> categorizedDecls =
        MultimapBuilder.hashKeys().arrayListValues().build();
    for (BodyDeclaration innerDecl : getInnerDeclarations()) {
      // The method constructor <init> methods will be marked here as inner declarations
      // Skip them as they are turned into OCaml module level constructors
      if (innerDecl instanceof MethodDeclaration) {
        MethodDeclaration m = (MethodDeclaration)innerDecl;
        if (m.getExecutableElement().getSimpleName().contentEquals("<init>")) {
          continue;
        }
      }
      categorizedDecls.put(DeclarationCategory.categorize(innerDecl), innerDecl);
    }
    // Emit the categorized declarations using the declaration order of the category values.
    for (DeclarationCategory category : DeclarationCategory.values()) {
      List<BodyDeclaration> declarations = categorizedDecls.get(category);
      if (declarations.isEmpty()) {
        continue;
      }
      // Extract MethodDeclaration nodes so that they can be sorted.
      List<MethodDeclaration> methods = Lists.newArrayList();
      for (Iterator<BodyDeclaration> iter = declarations.iterator(); iter.hasNext(); ) {
        BodyDeclaration decl = iter.next();
        if (decl instanceof MethodDeclaration) {
          methods.add((MethodDeclaration) decl);
          iter.remove();
        }
      }
      Collections.sort(methods, METHOD_DECL_ORDER);
      printDeclarations(methods);
      printDeclarations(declarations);
    }
  }

  /**
   * Method comparator, suitable for documentation and code-completion lists.
   *
   * Sort ordering: constructors first, then alphabetical by name. If they have the
   * same name, then compare the first parameter's simple type name, then the second, etc.
   */
  @VisibleForTesting
  static final Comparator<MethodDeclaration> METHOD_DECL_ORDER =
      new Comparator<MethodDeclaration>() {
    @Override
    public int compare(MethodDeclaration m1, MethodDeclaration m2) {
      if (m1.isConstructor() && !m2.isConstructor()) {
        return -1;
      }
      if (!m1.isConstructor() && m2.isConstructor()) {
        return 1;
      }
      String m1Name = ElementUtil.getName(m1.getExecutableElement());
      String m2Name = ElementUtil.getName(m2.getExecutableElement());
      if (!m1Name.equals(m2Name)) {
        return m1Name.compareToIgnoreCase(m2Name);
      }
      int nParams = m1.getParameters().size();
      int nOtherParams = m2.getParameters().size();
      int max = Math.min(nParams, nOtherParams);
      for (int i = 0; i < max; i++) {
        String paramType = TypeUtil.getName(m1.getParameter(i).getType().getTypeMirror());
        String otherParamType = TypeUtil.getName(m2.getParameter(i).getType().getTypeMirror());
        if (!paramType.equals(otherParamType)) {
          return paramType.compareToIgnoreCase(otherParamType);
        }
      }
      return nParams - nOtherParams;
    }
  };
}
