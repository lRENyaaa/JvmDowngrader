package xyz.wagyourtail.jvmdg.compile;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.wagyourtail.jvmdg.ClassDowngrader;
import xyz.wagyourtail.jvmdg.Constants;
import xyz.wagyourtail.jvmdg.cli.Flags;
import xyz.wagyourtail.jvmdg.compile.shade.ReferenceGraph;
import xyz.wagyourtail.jvmdg.util.*;
import xyz.wagyourtail.jvmdg.version.map.FullyQualifiedMemberNameAndDesc;
import xyz.wagyourtail.jvmdg.version.map.MemberNameAndDesc;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ApiShader {

    @Deprecated
    public static void main(String[] args) throws IOException {
        long start = System.currentTimeMillis();
        String prefix = args[1];
        File input = new File(args[2]);
        File output = new File(args[3]);
        int target = -1;
        File downgradedApi = null;
        if (args[0].matches("\\d+")) {
            target = Integer.parseInt(args[0]);
        } else {
            downgradedApi = new File(args[0]);
        }
        Flags flags = new Flags();
        flags.classVersion = target;
        shadeApis(flags, prefix, input, output, Collections.singleton(downgradedApi));
        System.out.println("Shaded in " + (System.currentTimeMillis() - start) + "ms");
    }

    public static void shadeApis(Flags flags, String prefix, File input, File output, Set<File> downgradedApi) throws IOException {
        if (output.exists()) {
            output.delete();
        }
        if (!prefix.endsWith("/")) {
            prefix += "/";
        }
        List<FileSystem> apiFs = new ArrayList<>();
        try {
            for (File file : downgradedApi) {
                apiFs.add(Utils.openZipFileSystem(file.toPath(), false));
            }

            try (FileSystem inputFs = Utils.openZipFileSystem(input.toPath(), false)) {
                try (FileSystem outputFs = Utils.openZipFileSystem(output.toPath(), true)) {
                    List<Path> apiRoots = new ArrayList<>();
                    for (FileSystem fs : apiFs) {
                        apiRoots.add(fs.getPath("/"));
                    }
                    Pair<ReferenceGraph, Set<Type>> api = scanApis(apiRoots);
                    shadeApis(prefix, inputFs.getPath("/"), outputFs.getPath("/"), apiRoots, api.getFirst(), api.getSecond());
                }
            }
        } finally {
            for (FileSystem fs : apiFs) {
                fs.close();
            }
        }
    }

    public static void shadeApis(Flags flags, String prefix, Path inputRoot, Path outputRoot, Set<File> downgradedApi) throws IOException {
        shadeApis(flags, Collections.singletonList(prefix), Collections.singletonList(inputRoot), Collections.singletonList(outputRoot), downgradedApi);
    }

    public static void shadeApis(Flags flags, List<String> prefix, List<Path> inputRoots, List<Path> outputRoots, Set<File> downgradedApi) throws IOException {
        for (String p : prefix) {
            if (!p.endsWith("/")) {
                throw new IllegalArgumentException("prefix \""+ p +"\" must end with /");
            }
        }
        Set<Path> downgradedApiPath = resolveDowngradedApi(flags, downgradedApi);
        List<FileSystem> apiFs = new ArrayList<>();
        try {
            for (File file : downgradedApi) {
                apiFs.add(Utils.openZipFileSystem(file.toPath(), false));
            }
            List<Path> apiRoots = new ArrayList<>();
            for (FileSystem fs : apiFs) {
                apiRoots.add(fs.getPath("/"));
            }
            Pair<ReferenceGraph, Set<Type>> api = scanApis(apiRoots);
            for (int i = 0; i < inputRoots.size(); i++) {
                shadeApis(prefix.get(i % prefix.size()), inputRoots.get(i), outputRoots.get(i), apiRoots, api.getFirst(), api.getSecond());
            }
        } finally {
            for (FileSystem fs : apiFs) {
                fs.close();
            }
        }
    }

    public static Set<Path> resolveDowngradedApi(Flags flags, @Nullable Set<File> downgradedApi) throws IOException {
        // step 1: downgrade the api to the target version
        Set<Path> downgradedApis = new HashSet<>();
        if (downgradedApi == null) {
            try (ClassDowngrader downgrader = ClassDowngrader.downgradeTo(flags)) {
                for (File file : flags.findJavaApi()) {
                    String name = file.getName();
                    int idx = name.lastIndexOf('.');
                    if (idx == -1) {
                        throw new IllegalArgumentException("File has no extension: " + name);
                    }
                    String beforeExt = name.substring(0, idx);
                    String ext = name.substring(idx);
                    Path targetPath = file.toPath().resolveSibling(beforeExt + "-downgraded" + flags.classVersion + ext);
                    ZipDowngrader.downgradeZip(downgrader, file.toPath(), new HashSet<URL>(), targetPath);
                    downgradedApis.add(targetPath);
                }
            }
        } else {
            for (File file : downgradedApi) {
                downgradedApis.add(file.toPath());
            }
        }
        return downgradedApis;
    }

    public static Pair<ReferenceGraph, Set<Type>> scanApis(List<Path> apiRoots) throws IOException {
        // step 2: collect classes in the api and their references
        try {
            ReferenceGraph apiRefs = new ReferenceGraph();
            List<Map<Path, Type>> preScans = new ArrayList<>();
            final Set<Type> apiClasses = new HashSet<>();
            for (Path apiRoot : apiRoots) {
                Map<Path, Type> preScan = apiRefs.preScan(apiRoot);
                preScans.add(preScan);
                apiClasses.addAll(preScan.values());
            }
            for (Map<Path, Type> preScan : preScans) {
                apiRefs.scan(preScan, new ReferenceGraph.Filter() {
                    @Override
                    public boolean shouldInclude(FullyQualifiedMemberNameAndDesc member) {
                        return apiClasses.contains(member.getOwner());
                    }
                });
            }
            return new Pair<>(apiRefs, apiClasses);
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

public static void shadeApis(final String prefix, final Path inputRoot, final Path outputRoot, final List<Path> apiRoots, final ReferenceGraph apiRefs, final Set<Type> apiClasses) throws IOException {
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("prefix must end with /");
        try {
            // step 3: traverse the input classes for references to the api
            final ReferenceGraph inputRefs = new ReferenceGraph(true);
            inputRefs.scan(inputRoot, new ReferenceGraph.Filter() {
                @Override
                public boolean shouldInclude(FullyQualifiedMemberNameAndDesc member) {
                    return apiClasses.contains(member.getOwner());
                }
            });
            // step 4: create remapper for api classes to prefixed api classes
            Pair<Set<FullyQualifiedMemberNameAndDesc>, Set<String>> required = apiRefs.recursiveResolveFrom(inputRefs.getAllRefs());
            final Map<Type, Set<MemberNameAndDesc>> byType = byType(required.getFirst());
            final Map<String, String> remap = new HashMap<>();
            for (Type type : byType.keySet()) {
                remap.put(type.getInternalName(), prefix + type.getInternalName());
            }
            final SimpleRemapper remapper = new SimpleRemapper(remap);

            // step 5: actually write the referenced api classes to the output, removing unused parts from them.
            Future<Void> apiWrite = AsyncUtils.forEachAsync(byType.entrySet(), new IOConsumer<Map.Entry<Type, Set<MemberNameAndDesc>>>() {
                @Override
                public void accept(Map.Entry<Type, Set<MemberNameAndDesc>> type) throws IOException {
                    Path outPath = outputRoot.resolve(prefix + type.getKey().getInternalName() + ".class");
                    Path parent = outPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    // load api class as a ClassNode
                    ClassNode node = apiRefs.getClassFor(type.getKey());
                    if ((node.access & Opcodes.ACC_ENUM) == 0) {
                        // remove unused members
                        Set<MemberNameAndDesc> members = type.getValue();
                        Iterator<MethodNode> iter = node.methods.iterator();
                        while (iter.hasNext()) {
                            MethodNode method = iter.next();
                            if (!members.contains(new MemberNameAndDesc(method.name, Type.getMethodType(method.desc)))) {
                                iter.remove();
                            }
                        }
                        Iterator<FieldNode> fiter = node.fields.iterator();
                        while (fiter.hasNext()) {
                            FieldNode field = fiter.next();
                            if (!members.contains(new MemberNameAndDesc(field.name, Type.getType(field.desc)))) {
                                fiter.remove();
                            }
                        }
                    }
                    // write the class to the output
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(new ClassRemapper(writer, remapper));
                    Files.write(outPath, writer.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }

            });

            Future<Void> resourceWrite = AsyncUtils.forEachAsync(required.getSecond(), new IOConsumer<String>() {
                @Override
                public void accept(String resource) throws IOException {
                    for (Path apiRoot : apiRoots) {
                        Path inPath = apiRoot.resolve(resource);
                        if (Files.exists(inPath)) {
                            Path outPath = outputRoot.resolve(resource);
                            Path parent = outPath.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(inPath, outPath, StandardCopyOption.REPLACE_EXISTING);
                            return;
                        }
                    }
                }
            });

            // step 6: remap references to the api to start with prefix
            Future<Void> shadeWrite = AsyncUtils.visitPathsAsync(inputRoot, new IOFunction<Path, Boolean>() {
                @Override
                public Boolean apply(Path path) throws IOException {
                    Path output = outputRoot.resolve(inputRoot.relativize(path).toString());
                    Files.createDirectories(output);
                    return true;
                }
            }, new IOConsumer<Path>() {
                @Override
                public void accept(Path path) throws IOException {
                    Path relPath = inputRoot.relativize(path);
                    Path output = outputRoot.resolve(relPath.toString());
                    String pathStr = relPath.toString();
                    if (!pathStr.startsWith("META-INF/versions") && pathStr.endsWith(".class") && !pathStr.equals("module-info.class")) {
//                                byte[] data = Files.readAllBytes(path);
                        ClassWriter writer = new ClassWriter(0);
                        pathStr = pathStr.substring(0, pathStr.length() - 6);
                        ClassNode cn = inputRefs.getClassFor(Type.getObjectType(pathStr));
                        if (cn == null) {
                            throw new IllegalStateException("Class not found from ref cache? " + pathStr);
                        }
                        cn.accept(new ClassRemapper(writer, remapper));
                        Files.write(output, writer.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    } else {
                        Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            });

            AsyncUtils.waitForFutures(apiWrite, resourceWrite, shadeWrite).get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<Type, Set<MemberNameAndDesc>> byType(Set<FullyQualifiedMemberNameAndDesc> fqns) {
        Map<Type, Set<MemberNameAndDesc>> byType = new HashMap<>();
        for (FullyQualifiedMemberNameAndDesc fqn : fqns) {
            Set<MemberNameAndDesc> list = byType.get(fqn.getOwner());
            if (list == null) {
                list = new HashSet<>();
                byType.put(fqn.getOwner(), list);
            }
            MemberNameAndDesc member = fqn.toMemberNameAndDesc();
            if (member != null) {
                list.add(member);
            }
        }
        return byType;
    }

}
