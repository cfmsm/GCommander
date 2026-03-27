package com.github.cfmsm.gcommander;
import static com.github.cfmsm.gcommander.GCommander.*;
public class GCommandBuilder {
    public static String header(Class<?> t) {

        return """
                #version 450
                layout(std430, set=0, binding=0) buffer InputBuf { %type% data[]; } inputBuf;
                layout(std430, set=0, binding=1) buffer OutputBuf { %type% data[]; } outputBuf;
                layout(local_size_x=%optimal%, local_size_y=1, local_size_z=1) in;
                """.replace("%type%", t.getName()).replace("%optimal%", String.valueOf(getGpuOptimalValue()));
    } public static int getGpuOptimalValue() {
        if (cards.isEmpty()) {
            return 32;
        }


        if (vendor.contains("intel") || vendor.contains("apple")) {
            return 32;
        } else if (vendor.contains("amd") || vendor.contains("nvidia") || vendor.contains("ati")) {
            return 64;
        }

        return 0;
    }
}