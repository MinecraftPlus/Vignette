package net.minecraftforge.lex;

import java.io.Reader;
import java.io.Writer;
import java.util.Optional;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.lorenz.io.MappingsWriter;
import org.cadixdev.lorenz.io.TextMappingFormat;
import org.cadixdev.lorenz.io.TextMappingsReader;
import org.cadixdev.lorenz.io.srg.SrgConstants;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.MethodMapping;
import org.objectweb.asm.Type;

public class TSrg2Format implements TextMappingFormat {
    @Override public MappingsReader createReader(final Reader reader) { return new TSrg2Reader(reader); }
    @Override public MappingsWriter createWriter(final Writer writer) { throw new UnsupportedOperationException("No writing TSRGv2"); }
    @Override public Optional<String> getStandardFileExtension() { return Optional.empty(); }
    @Override public String toString() { return "tsrg2"; }

    private static class TSrg2Reader extends TextMappingsReader {

        protected TSrg2Reader(Reader reader) {
            super(reader, TSrg2Reader.Processor::new);
        }

        @SuppressWarnings("rawtypes")
        private static class Processor extends TextMappingsReader.Processor {
            private int nameCount = 0;
            private ClassMapping cls;
            private MethodMapping mtd;
            private int[] pidx;

            protected Processor(MappingSet mappings) {
                super(mappings);
            }

            @Override
            public void accept(String raw) {
                final String line = SrgConstants.removeComments(raw);
                if (line.isEmpty()) return;

                if (line.startsWith("tsrg2 ")) {
                    nameCount = line.split(" ").length - 1;
                    return;
                }

                String[] pts = line.split(" ");
                if (pts[0].charAt(0) != '\t') {
                    if (pts.length != nameCount) error(line);

                    if (pts[0].charAt(pts[0].length() - 1) != '/') {
                        this.cls = this.mappings.getOrCreateClassMapping(pts[0]);
                        this.cls.setDeobfuscatedName(pts[1]);
                    }
                } else if (pts[0].charAt(1) == '\t') {
                    if (mtd == null) error(line);
                    pts[0] = pts[0].substring(2);
                    if (pts.length == 1 && "static".equals(pts[0]))
                        for (int x = 0; x < pidx.length; x++)
                            pidx[x]--;
                    else if (pts.length == nameCount + 1)
                        mtd.getOrCreateParameterMapping(pidx[Integer.parseInt(pts[0])]).setDeobfuscatedName(pts[2]);
                    else
                        error(line);
                } else {
                    if (cls == null) error(line);
                    pts[0] = pts[0].substring(1);
                    if (pts.length == nameCount)
                        cls.getOrCreateFieldMapping(pts[0]).setDeobfuscatedName(pts[1]);
                    else if (pts.length == nameCount + 1) {
                        if (pts[1].charAt(0) == '(') {
                            mtd = cls.getOrCreateMethodMapping(pts[0], pts[1]).setDeobfuscatedName(pts[2]);
                            Type[] args = Type.getArgumentTypes(pts[1]);
                            pidx = new int[args.length];
                            int i = 1;
                            for (int x = 0; x < args.length; x++) {
                                pidx[x] = i;
                                i += args[x].getSize();
                            }
                        } else {
                            mtd = null;
                            pidx = null;
                            cls.getOrCreateFieldMapping(pts[0], pts[1]).setDeobfuscatedName(pts[2]);
                        }
                    }
                }

            }

            private void error(String line) { throw new IllegalArgumentException("Failed to process line: `" + line + "`!"); }
        }
    }
}
