package com.github.cfmsm.gcommander;
public class GCommandBuilder {
    public static final String UINT32 = "uint";
    public static final String INT32 = "int";
    public static final String FLOAT32 = "float";
    public static final String UNSIGNED_INT32 = UINT32;
    public static final String INTEGER32 = INT32;

    public static String header(String t) {
        return """
                #version 450
                layout(std430, set=0, binding=0) buffer InputBuf { %type% data[]; } inputBuf;
                layout(std430, set=0, binding=1) buffer OutputBuf { %type% data[]; } outputBuf;
                """.replace("%type%", t);
    }
}