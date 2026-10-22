package edu.tongji.cst3492.ta.llm;

/**
 * Captures the user's current programming context in the IntelliJ IDEA editor
 * (active file, enclosing class/method, selected line range, and selected code snippet).
 */
public record EditorCodeContext(
        String fileName,
        String className,
        String methodName,
        int startLine,
        int endLine,
        String selectedCode
) {
    public static EditorCodeContext empty() {
        return new EditorCodeContext("", "", "", 0, 0, "");
    }

    public boolean hasSelection() {
        return selectedCode != null && !selectedCode.isBlank();
    }

    public String formatSummary() {
        if (!hasSelection()) {
            return "No code selected";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(fileName == null || fileName.isBlank() ? "Editor Selection" : fileName);
        if (startLine > 0) {
            sb.append(" (Lines ").append(startLine).append("-").append(Math.max(startLine, endLine)).append(")");
        }
        if (className != null && !className.isBlank()) {
            sb.append(" in class ").append(className);
        }
        if (methodName != null && !methodName.isBlank()) {
            sb.append("#").append(methodName).append("()");
        }
        return sb.toString();
    }
}
