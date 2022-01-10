package net.minecraftforge.lex;

import static java.util.jar.Attributes.Name.MAIN_CLASS;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.stream.Collectors;

import org.cadixdev.atlas.AtlasTransformerContext;
import org.cadixdev.bombe.jar.AbstractJarEntry;
import org.cadixdev.bombe.jar.JarClassEntry;
import org.cadixdev.bombe.jar.JarEntryTransformer;
import org.cadixdev.bombe.jar.JarManifestEntry;
import org.cadixdev.bombe.jar.JarResourceEntry;
import org.cadixdev.bombe.jar.JarServiceProviderConfigurationEntry;
import org.cadixdev.bombe.jar.ServiceProviderConfiguration;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.asm.LorenzRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public class EnhancedRemappingTransformer implements JarEntryTransformer, ExtendedClassRemapper.AbstractConsumer {
    private final boolean makeFFMeta;
    private final Set<String> abstractParams = ConcurrentHashMap.newKeySet();

    public EnhancedRemappingTransformer(MappingSet mappings, AtlasTransformerContext ctx, boolean makeFFMeta) {
        this.makeFFMeta = makeFFMeta;

        this.remapper = new LorenzRemapper(mappings, ctx.inheritanceProvider());
        this.clsRemapper = (cv, remapper) -> new ExtendedClassRemapper(cv, remapper, mappings, ctx.inheritanceProvider(), this);
    }

    @Override
    public List<AbstractJarEntry> additions() {
        if (!makeFFMeta || abstractParams.isEmpty())
            return Collections.emptyList();
        byte[] data = abstractParams.stream().sorted().collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8);
        return Arrays.asList(new JarResourceEntry("fernflower_abstract_parameter_names.txt", 1, data));
    }

    @Override
    public void storeNames(String className, String methodName, String methodDescriptor, Collection<String> paramNames) {
        abstractParams.add(className + ' ' + methodName + ' ' + methodDescriptor + ' ' + paramNames.stream().collect(Collectors.joining(" ")));
    }



    // Copied from Bombe, because there is no easy way to add enclosing context to the existing one.

    private static final Attributes.Name SHA_256_DIGEST = new Attributes.Name("SHA-256-Digest");

    private final Remapper remapper;
    private final BiFunction<ClassVisitor, Remapper, ClassRemapper> clsRemapper;
    @Override
    public JarClassEntry transform(final JarClassEntry entry) {
        // Remap the class
        final ClassReader reader = new ClassReader(entry.getContents());
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(this.clsRemapper.apply(
                writer,
                this.remapper
        ), 0);

        // Create the jar entry
        final String originalName = entry.getName().substring(0, entry.getName().length() - ".class".length());
        final String name = this.remapper.map(originalName) + ".class";
        return new JarClassEntry(name, entry.getTime(), writer.toByteArray());
    }

    @Override
    public JarManifestEntry transform(final JarManifestEntry entry) {
        // Remap the Main-Class attribute, if present
        if (entry.getManifest().getMainAttributes().containsKey(MAIN_CLASS)) {
            final String mainClassObf = entry.getManifest().getMainAttributes().getValue(MAIN_CLASS)
                    .replace('.', '/');
            final String mainClassDeobf = this.remapper.map(mainClassObf)
                    .replace('/', '.');

            // Since Manifest is mutable, we needn't create a new entry \o/
            entry.getManifest().getMainAttributes().put(MAIN_CLASS, mainClassDeobf);
        }

        // Remove all signature entries
        for (final Iterator<Map.Entry<String, Attributes>> it = entry.getManifest().getEntries().entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, Attributes> section = it.next();
            if (section.getValue().remove(SHA_256_DIGEST) != null) {
                if (section.getValue().isEmpty()) {
                    it.remove();
                }
            }
        }

        return entry;
    }

    @Override
    public JarServiceProviderConfigurationEntry transform(final JarServiceProviderConfigurationEntry entry) {
        // Remap the Service class
        final String obfServiceName = entry.getConfig().getService()
                .replace('.', '/');
        final String deobfServiceName = this.remapper.map(obfServiceName)
                .replace('/', '.');

        // Remap the Provider classes
        final List<String> deobfProviders = entry.getConfig().getProviders().stream()
                .map(provider -> provider.replace('.', '/'))
                .map(this.remapper::map)
                .map(provider -> provider.replace('/', '.'))
                .collect(Collectors.toList());

        // Create the new entry
        final ServiceProviderConfiguration config = new ServiceProviderConfiguration(deobfServiceName, deobfProviders);
        return new JarServiceProviderConfigurationEntry(entry.getTime(), config);
    }

    @Override
    public JarResourceEntry transform(final JarResourceEntry entry) {
        // Strip signature files from metadata
        if (entry.getName().startsWith("META-INF")) {
            if (entry.getExtension().equals("RSA")
                || entry.getExtension().equals("SF")) {
                return null;
            }
        }
        return entry;
    }
    // END COPY
}
