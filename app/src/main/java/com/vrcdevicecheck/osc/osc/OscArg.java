package com.vrcdevicecheck.osc.osc;

/**
 * OSC 参数。类型标签遵循 OSC 1.0 规范：
 * s 字符串 / i int32 / f float32 / T,F 布尔（不占数据位）/ b blob
 */
public final class OscArg {

    public enum Type { STRING, INT32, FLOAT32, BOOL, BLOB }

    public final Type type;
    private final String stringValue;
    private final int intValue;
    private final float floatValue;
    private final boolean boolValue;
    private final byte[] blobValue;

    private OscArg(Type type, String s, int i, float f, boolean b, byte[] blob) {
        this.type = type;
        this.stringValue = s;
        this.intValue = i;
        this.floatValue = f;
        this.boolValue = b;
        this.blobValue = blob;
    }

    public static OscArg of(String value) {
        return new OscArg(Type.STRING, value == null ? "" : value, 0, 0f, false, null);
    }

    public static OscArg of(int value) {
        return new OscArg(Type.INT32, null, value, 0f, false, null);
    }

    public static OscArg of(float value) {
        return new OscArg(Type.FLOAT32, null, 0, value, false, null);
    }

    public static OscArg of(boolean value) {
        return new OscArg(Type.BOOL, null, 0, 0f, value, null);
    }

    public static OscArg ofBlob(byte[] value) {
        return new OscArg(Type.BLOB, null, 0, 0f, false, value == null ? new byte[0] : value);
    }

    public char tag() {
        switch (type) {
            case STRING:
                return 's';
            case INT32:
                return 'i';
            case FLOAT32:
                return 'f';
            case BLOB:
                return 'b';
            case BOOL:
            default:
                return boolValue ? 'T' : 'F';
        }
    }

    public String stringValue() {
        return stringValue;
    }

    public int intValue() {
        return intValue;
    }

    public float floatValue() {
        return floatValue;
    }

    public boolean boolValue() {
        return boolValue;
    }

    public byte[] blobValue() {
        return blobValue;
    }
}
