package org.minecraftplus;

import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.minecraftplus.srgprocessor.Dictionary;
import org.minecraftplus.srgprocessor.Utils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ExtendedDeducingClassRemapper extends ClassRemapper {

    interface AbstractConsumer {
        void storeNames(String className, String methodName, String methodDescriptor, Collection<String> paramNames);
    }

    private final MappingSet mappings;
    private final Set<org.minecraftplus.srgprocessor.Dictionary> dictionaries;
    private final InheritanceProvider inheritanceProvider;
    private final AbstractConsumer abstractConsumer;

    ExtendedDeducingClassRemapper(ClassVisitor classVisitor, Remapper remapper, MappingSet mappings, Set<org.minecraftplus.srgprocessor.Dictionary> dictionaries, InheritanceProvider inheritanceProvider, AbstractConsumer abstractConsumer) {
        super(classVisitor, remapper);
        this.mappings = mappings;
        this.dictionaries = dictionaries;
        this.inheritanceProvider = inheritanceProvider;
        this.abstractConsumer = abstractConsumer;
    }


    //Lex: Added field for supporting remapping lambdas, Potentially need to support other JDK's?
    private static final Handle META_FACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
    private static final Handle ALT_META_FACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String mname, final String mdescriptor, final String msignature, final String[] exceptions) {
        // Clear used names on each pass
        final Set<String> usedNames = new HashSet<>();
        String remappedDescriptor = remapper.mapMethodDesc(mdescriptor);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

        MethodVisitor methodVisitor = cv.visitMethod(access, remapper.mapMethodName(className, mname, mdescriptor), remappedDescriptor, remapper.mapSignature(msignature, false), exceptions == null ? null : remapper.mapTypes(exceptions));
        if (methodVisitor == null)
            return null;

        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
            renameAbstract(access, mname, mdescriptor, remappedDescriptor, usedNames);

        return new MethodRemapper(methodVisitor, remapper) {
            @Override
            public void visitLocalVariable(final String pname, final String pdescriptor, final String psignature, final Label start, final Label end, final int index) {
                String rename = mapParameterName(className, mname, mdescriptor, index, pname, pdescriptor, usedNames);
                super.visitLocalVariable(checkName(rename), pdescriptor, psignature, start, end, index);
            }

            private String checkName(String name) {
                // Snowmen, added in 1.8.2? Check them names that can exist in source
                if (0x2603 == name.charAt(0))
                    throw new IllegalStateException("Cannot be Snowman here!");

                // Protect against protected java keywords as parameter name
                // Ignore 'this' as it can be 0 index parameter
                if (!name.equals("this") && Utils.JAVA_KEYWORDS.contains(name))
                    throw new IllegalStateException("Parameter name cannot be equal to java keywords: " + name);

                return name;
            }

            @Override
            public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bootstrapMethodHandle, final Object... bootstrapMethodArguments) {
                if (META_FACTORY.equals(bootstrapMethodHandle) || ALT_META_FACTORY.equals(bootstrapMethodHandle)) {
                    String owner = Type.getReturnType(descriptor).getInternalName();
                    String odesc = ((Type)bootstrapMethodArguments[0]).getDescriptor();
                                   // First constant argument is "samMethodType - Signature and return type of method to be implemented by the function object."
                                   // index 2 is the signature, but with generic types. Should we use that instead?

                    // We can't call super, because that'd double map the name.
                    // So we do our own mapping.
                    Object[] remappedBootstrapMethodArguments = new Object[bootstrapMethodArguments.length];
                    for (int i = 0; i < bootstrapMethodArguments.length; ++i) {
                      remappedBootstrapMethodArguments[i] = remapper.mapValue(bootstrapMethodArguments[i]);
                    }
                    mv.visitInvokeDynamicInsn(
                        remapper.mapMethodName(owner, name, odesc), // We change this
                        remapper.mapMethodDesc(descriptor),
                        (Handle) remapper.mapValue(bootstrapMethodHandle),
                        remappedBootstrapMethodArguments);
                    return;
                }

                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            }
        };
    }

    private ClassMapping<?, ?> getCompletedClassMapping(final String owner) {
        final ClassMapping<?, ?> mapping = this.mappings.getOrCreateClassMapping(owner);
        mapping.complete(this.inheritanceProvider);
        return mapping;
    }

    public String mapParameterName(final String owner, final String methodName, final String methodDescriptor, final int index, final String paramName, String pdescriptor, Set<String> usedNames) {
        return this.getCompletedClassMapping(owner)
                .getMethodMapping(MethodSignature.of(methodName, methodDescriptor))
                .flatMap(m -> m.getParameterMapping(index))
                .map(Mapping::getDeobfuscatedName)
                .orElse(deduceName(owner, methodName, methodDescriptor, index, paramName, pdescriptor, usedNames));
    }

    public String deduceName(final String owner, final String methodName, final String methodDescriptor, final int index, final String paramName, String pdescriptor, Set<String> usedNames) {
        // Don't deduce these
        if (paramName.equalsIgnoreCase("this"))
            return paramName;

        // Find parameter type class from descriptor
        String parameterDescriptor = remapper.mapDesc(pdescriptor);
        String parameterType;
        Matcher matcher = Utils.DESC.matcher(parameterDescriptor);
        if (matcher.find()) {
            parameterType = matcher.group("cls");
            if (parameterType == null)
                parameterType = matcher.group();
        } else
            throw new IllegalStateException("Invalid prameter descriptor: " + parameterDescriptor);

        // Extract only class name from type
        String parameterName = parameterType.substring(parameterType.lastIndexOf("/") + 1);
        parameterName = parameterName.substring(parameterName.lastIndexOf("$") + 1); // Use last inner class name

        // Add 'a' prefix to parameters which are arrays
        if (parameterDescriptor.startsWith("["))//parameterDescriptor.isArray())
            parameterName = "a" + parameterName;

        // Deduce parameter name from class type and rules in dictionary
        for (org.minecraftplus.srgprocessor.Dictionary dictionary : dictionaries) {
            for (Map.Entry<org.minecraftplus.srgprocessor.Dictionary.Trigger, org.minecraftplus.srgprocessor.Dictionary.Action> rule : dictionary.getRules().entrySet()) {
                org.minecraftplus.srgprocessor.Dictionary.Trigger trigger = rule.getKey();

                Pattern filter = trigger.getFilter();
                if (filter != null && !filter.matcher(parameterType).matches()) {
                    continue; // Skip dictionary replaces if filter not pass
                }

                Pattern pattern = trigger.getPattern();
                Dictionary.Action action = rule.getValue();
                Matcher ruleMatcher = pattern.matcher(parameterName);
                if (ruleMatcher.matches()) { // Only one replace at time
                    parameterName = action.act(ruleMatcher);
                }
            }
        }

        // Always lowercase parameter name
        String deduced = parameterName.toLowerCase(Locale.ROOT);

        // Store used name and add number after if duplicates
        int counter = 1;
        String ret = deduced;
        while (!usedNames.add(ret)) {
            ret = deduced + String.valueOf(counter);
            counter++;
        }

        return ret;
    }

    private void renameAbstract(int access, String name, String descriptor, String pdescriptor, Set<String> usedNames) {
        Type[] types = Type.getArgumentTypes(descriptor);
        if (types.length == 0)
            return;

        List<String> names = new ArrayList<>();
        int i = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : types) {
            names.add(mapParameterName(className, name, descriptor, i, "var" + i, pdescriptor, usedNames));
            i += type.getSize();
        }

        abstractConsumer.storeNames(
            remapper.mapType(className),
            remapper.mapMethodName(className, name, descriptor),
            remapper.mapMethodDesc(descriptor),
            names
        );
    }
}
