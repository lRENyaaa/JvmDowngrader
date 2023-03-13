package xyz.wagyourtail.jvmdg.standalone;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import xyz.wagyourtail.jvmdg.Constants;
import xyz.wagyourtail.jvmdg.VersionProvider;
import xyz.wagyourtail.jvmdg.standalone.classloader.DowngradingClassLoader;

import java.io.*;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ClassDowngrader {
    private static final Map<Integer, String> versionDowngraders = new HashMap<>();
    public static final DowngradingClassLoader classLoader;

    static {
        versionDowngraders.put(Opcodes.V9, "xyz.wagyourtail.jvmdg.j9.Java9Downgrader");
        versionDowngraders.put(Opcodes.V10, "xyz.wagyourtail.jvmdg.j10.Java10Downgrader");
        versionDowngraders.put(Opcodes.V11, "xyz.wagyourtail.jvmdg.j11.Java11Downgrader");
        versionDowngraders.put(Opcodes.V12, "xyz.wagyourtail.jvmdg.j12.Java12Downgrader");
        versionDowngraders.put(Opcodes.V13, "xyz.wagyourtail.jvmdg.j13.Java13Downgrader");
        versionDowngraders.put(Opcodes.V14, "xyz.wagyourtail.jvmdg.j14.Java14Downgrader");
        versionDowngraders.put(Opcodes.V15, "xyz.wagyourtail.jvmdg.j15.Java15Downgrader");
        versionDowngraders.put(Opcodes.V16, "xyz.wagyourtail.jvmdg.j16.Java16Downgrader");
        versionDowngraders.put(Opcodes.V17, "xyz.wagyourtail.jvmdg.j17.Java17Downgrader");
        classLoader = new DowngradingClassLoader(new URL[]{findJavaApi()}, ClassDowngrader.class.getClassLoader());
    }

    private static final Map<Integer, VersionProvider> downgraders = new HashMap<>();

    private int target;

    public ClassDowngrader(int versionTarget) {
        this.target = versionTarget;
    }

    private static URL findJavaApi() {
        try {
            InputStream stream = ClassDowngrader.class.getResourceAsStream("/META-INF/lib/java-api.jar");
            if (stream == null) {
                // get from java properties
                String api = System.getProperty("jvmdg.java-api");
                if (api == null) {
                    throw new RuntimeException("Could not find java-api.jar");
                }
                    return new File(api).toURI().toURL();
            } else {
                // copy to temp file
                File temp = new File(Constants.DIR, "java-api.jar");
                if (temp.exists()) temp.delete();
                try (FileOutputStream fos = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = stream.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                return temp.toURI().toURL();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("IOStreamConstructor")
    public void downgradeZip(File zip, File output) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".class")) {
                        ClassNode clazz = new ClassNode();
                        new ClassReader(zis).accept(clazz, 0);
                        downgrade(clazz);
                        ZipEntry newEntry = new ZipEntry(entry.getName());
                        newEntry.setTime(entry.getTime());
                        zos.putNextEntry(newEntry);
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                        try {
                            clazz.accept(writer);
                        } catch (Throwable t) {
                            System.err.println("Error while writing class " + clazz.name);
                            throw t;
                        }
                        zos.write(writer.toByteArray());
                        zos.closeEntry();
                    } else {
                        zos.putNextEntry(entry);
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                        zos.closeEntry();
                    }
                }
            } catch (InvocationTargetException | IllegalAccessException | ClassNotFoundException |
                     NoSuchMethodException | InstantiationException e) {
                output.delete();
                throw new RuntimeException(e);
            }
        }
    }

    public void downgrade(ClassNode clazz) throws InvocationTargetException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException {
        while (clazz.version > target) {
            int i = clazz.version;
            if (!downgraders.containsKey(i)) {
                synchronized (downgraders) {
                    if (!downgraders.containsKey(i)) {
                        if (!versionDowngraders.containsKey(i)) {
                            throw new RuntimeException("Unsupported class version: " + i);
                        }
                        VersionProvider p = (VersionProvider) Class.forName(versionDowngraders.get(i), true, classLoader).getConstructor().newInstance();
                        downgraders.put(i, p);
                    }
                }
            }
            downgraders.get(i).downgrade(clazz);
        }
    }

    public byte[] downgrade(String name, byte[] bytes) throws IllegalClassFormatException {
        // check magic
        if (bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE || bytes[2] != (byte) 0xBA ||
            bytes[3] != (byte) 0xBE) {
            throw new IllegalClassFormatException(name);
        }
        // ignore minor version
        // get major version
        int version = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        if (version <= target) {
            // already at or below the target version
            return bytes;
        }
        // read into a ClassReader
        ClassReader cr = new ClassReader(bytes);
        // convert to class node
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        // apply the downgrade
        try {
            if (Constants.DEBUG) System.out.println("Transforming " + name);
            downgrade(node);
        } catch (InvocationTargetException | IllegalAccessException | ClassNotFoundException | NoSuchMethodException |
                 InstantiationException e) {
            throw new RuntimeException(e);
        }

        // write the class back out
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        // define the class
        bytes = cw.toByteArray();
        if (Constants.DEBUG) {
            System.out.println("Downgraded " + name + " from " + version + " to " + target);
            writeBytesToDebug(name, bytes);
        }
        return bytes;
    }

    public void writeBytesToDebug(String name, byte[] bytes) {
        File f = new File(Constants.DEBUG_DIR, name.replace('.', '/') + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bytes);
        } catch (IOException ignored) {
        }
    }

    public static void main(String[] args) {
        //TODO
    }

}