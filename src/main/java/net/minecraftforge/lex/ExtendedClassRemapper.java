package net.minecraftforge.lex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

class ExtendedClassRemapper extends ClassRemapper {
    interface AbstractConsumer {
        void storeNames(String className, String methodName, String methodDescriptor, Collection<String> paramNames);
    }

    private final MappingSet mappings;
    private final InheritanceProvider inheritanceProvider;
    private final AbstractConsumer abstractConsumer;

    ExtendedClassRemapper(ClassVisitor classVisitor, Remapper remapper, MappingSet mappings, InheritanceProvider inheritanceProvider, AbstractConsumer abstractConsumer) {
        super(classVisitor, remapper);
        this.mappings = mappings;
        this.inheritanceProvider = inheritanceProvider;
        this.abstractConsumer = abstractConsumer;
    }


    //Lex: Added field for supporting remapping lambdas, Potentially need to support other JDK's?
    private static final Handle META_FACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
    private static final Handle ALT_META_FACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");

    @Override
    public MethodVisitor visitMethod(final int access, final String mname, final String mdescriptor, final String msignature, final String[] exceptions) {
        String remappedDescriptor = remapper.mapMethodDesc(mdescriptor);
        MethodVisitor methodVisitor = cv.visitMethod(access, remapper.mapMethodName(className, mname, mdescriptor), remappedDescriptor, remapper.mapSignature(msignature, false), exceptions == null ? null : remapper.mapTypes(exceptions));
        if (methodVisitor == null)
            return null;

        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
            renameAbstract(access, mname, mdescriptor);

        return new MethodRemapper(methodVisitor, remapper) {
            @Override
            public void visitLocalVariable(final String pname, final String pdescriptor, final String psignature, final Label start, final Label end, final int index) {
                super.visitLocalVariable(renameSnowmen(mapParameterName(className, mname, mdescriptor, index, pname), index), pdescriptor, psignature, start, end, index);
            }

            // Snowmen, added in 1.8.2? rename them names that can exist in source
            private final Map<Integer, Integer> seen = new HashMap<>();
            private String renameSnowmen(String name, int index) {
                if (0x2603 != name.charAt(0))
                    return name;
                int version = seen.computeIfAbsent(index, k -> 0) + 1;
                seen.put(index, version);
                return "lvt_" + index + '_' + version + '_';
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

    public String mapParameterName(final String owner, final String methodName, final String methodDescriptor, final int index, final String paramName) {
        return this.getCompletedClassMapping(owner)
                .getMethodMapping(MethodSignature.of(methodName, methodDescriptor))
                .flatMap(m -> m.getParameterMapping(index))
                .map(Mapping::getDeobfuscatedName)
                .orElse(paramName);
    }

    private void renameAbstract(int access, String name, String descriptor) {
        Type[] types = Type.getArgumentTypes(descriptor);
        if (types.length == 0)
            return;

        List<String> names = new ArrayList<>();
        int i = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : types) {
            names.add(mapParameterName(className, name, descriptor, i, "var" + i));
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
