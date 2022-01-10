package net.minecraftforge.lex;

import static org.objectweb.asm.Opcodes.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.cadixdev.atlas.AtlasTransformerContext;
import org.cadixdev.bombe.analysis.InheritanceProvider;
import org.cadixdev.bombe.analysis.InheritanceProvider.ClassInfo;
import org.cadixdev.bombe.analysis.InheritanceType;
import org.cadixdev.bombe.jar.JarClassEntry;
import org.cadixdev.bombe.jar.JarEntryTransformer;
import org.cadixdev.bombe.type.BaseType;
import org.cadixdev.bombe.type.FieldType;
import org.cadixdev.bombe.type.MethodDescriptor;
import org.cadixdev.bombe.type.ObjectType;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

public class ConstructorInjector implements JarEntryTransformer {
    private static final MethodDescriptor EMPTY = MethodDescriptor.of("()V");
    private final InheritanceProvider inh;
    private final MappingSet o2m, m2o;

    public ConstructorInjector(AtlasTransformerContext ctx, MappingSet mappings) {
        this.inh = ctx.inheritanceProvider();
        this.o2m = mappings;
        this.m2o = mappings.reverse();
    }

    @Override
    public JarClassEntry transform(final JarClassEntry entry) {
        final ClassReader reader = new ClassReader(entry.getContents());
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new InitAdder(writer), 0);
        return new JarClassEntry(entry.getName(), entry.getTime(), writer.toByteArray());
    }

    private class InitAdder extends ClassVisitor {
        private String className, parentName, parentField;
        private ObjectType superType;
        private boolean hasInit = false;
        private boolean isStatic = false;
        private Map<String, FieldType> fields = new LinkedHashMap<>();

        public InitAdder(ClassVisitor cv) {
            super(ASM9, cv);
        }

        private void log(String message) {
            System.out.println(message);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            this.superType = new ObjectType(superName);
            this.isStatic = (access & ACC_STATIC) != 0;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override // The reader *should* read this before any fields/methods, so we can set the parent name to find the field
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (this.className.equals(name))
                this.parentName = "L" + outerName + ";";
            super.visitInnerClass(name, outerName, innerName, access);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            if ((access & ACC_STATIC) == 0 && (access & ACC_FINAL) == ACC_FINAL) {
                if (this.parentName != null && desc.equals(this.parentName) && (access & ACC_SYNTHETIC) == ACC_SYNTHETIC)
                    this.parentField = name;
                else
                    this.fields.put(name, FieldType.of(desc));
            }
            return super.visitField(access, name, desc, signature, value);
        }


        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if ("<init>".equals(name))
                hasInit = true;
            return super.visitMethod(access, name, desc, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            boolean isInner = parentField != null && parentName != null && !isStatic;
            if (hasInit) {
                super.visitEnd();
                return;
            }

            MethodDescriptor sup = findSuper(this.superType);
            if (!isInner && fields.isEmpty() && sup.equals(EMPTY)) {
                super.visitEnd();
                return;
            }

            log("  Adding synthetic <init> to " + className);

            MethodVisitor mv;
            if (isInner)
                mv = this.visitMethod(ACC_PRIVATE | (fields.isEmpty() ? ACC_SYNTHETIC : 0), "<init>", "(" + parentName + ")V", null, null);
            else
                mv = this.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);

            mv.visitVarInsn(ALOAD, 0);
            if (!sup.equals(EMPTY))
                log("    Super: " + sup);
            for (FieldType p : sup.getParamTypes())
                loadConstant(mv, p);
            mv.visitMethodInsn(INVOKESPECIAL, superType.getClassName(), "<init>", sup.toString(), false);

            if (isInner) {
                log("    Inner: " + parentName + " " + parentField);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitFieldInsn(PUTFIELD, className, parentField, parentName);
            }

            if (!fields.isEmpty()) {
                for (Entry<String, FieldType> entry : fields.entrySet()) {
                    mv.visitVarInsn(ALOAD, 0);
                    loadConstant(mv, entry.getValue());
                    mv.visitFieldInsn(PUTFIELD, this.className, entry.getKey(), entry.getValue().toString());
                    log("    Field: " + entry.getKey());
                }
            }

            mv.visitTypeInsn(NEW, "java/lang/RuntimeException");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("Synthetic constructor do not call");
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(ATHROW);
            super.visitEnd();
        }

        private MethodDescriptor findSuper(ObjectType parent) {
            boolean obfed = false;
            InheritanceProvider inh = ConstructorInjector.this.inh;
            MappingSet o2m = ConstructorInjector.this.o2m;
            MappingSet m2o = ConstructorInjector.this.m2o;

            Optional<ClassInfo> pcls = inh.provide(parent.getClassName());
            if (!pcls.isPresent()) {
                pcls = inh.provide(((ObjectType)m2o.deobfuscate(parent)).getClassName());
                if (pcls.isPresent())
                    obfed = true;
            }

            MethodDescriptor sig = null;
            if (pcls.isPresent()) {
                for (Entry<MethodSignature, InheritanceType> entry : pcls.get().getMethods().entrySet()) {
                    if (!"<init>".equals(entry.getKey().getName()) || entry.getValue() == InheritanceType.NONE)
                        continue;
                    MethodDescriptor edesc = entry.getKey().getDescriptor();
                    if (sig == null)
                        sig = edesc;
                    else if (edesc.getParamTypes().size() < sig.getParamTypes().size())
                        sig = edesc;
                    else if (edesc.getParamTypes().size() == sig.getParamTypes().size()) {
                        if (edesc.toString().compareTo(sig.toString()) < 0) // Simple string sort in case different ordering of the methods map.
                            sig = edesc;
                    }
                }
            }

            return sig == null ? EMPTY : obfed ? o2m.deobfuscate(sig) : sig;
        }

        private void loadConstant(MethodVisitor mv, FieldType type) {
            if (type instanceof BaseType) {
                switch ((BaseType)type) {
                    case BOOLEAN:
                    case BYTE:
                    case CHAR:
                    case INT:
                    case SHORT:  mv.visitInsn(ICONST_0); break;
                    case FLOAT:  mv.visitInsn(FCONST_0); break;
                    case LONG:   mv.visitInsn(LCONST_0); break;
                    case DOUBLE: mv.visitInsn(DCONST_0); break;
                    default:     mv.visitInsn(ACONST_NULL);
                }
            } else
                mv.visitInsn(ACONST_NULL);
        }
    }
}
