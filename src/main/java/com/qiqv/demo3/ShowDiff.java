package com.qiqv.demo3;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
//
//这个类用于解析生成的diff文件

public class ShowDiff {
    public static class Diff {
        public String originalFilePath; // --- 后面的文件路径
        public String targetFilePath;   // +++ 后面的文件路径
        public List<String> diffLines;  // 存储差异的行

        public Diff() {
            diffLines = new ArrayList<>();
        }
    }

    // 解析 .diff 文件
    public static Diff parseDiffFile(String diffFilePath) {
        Diff diff = new Diff();
        try (BufferedReader reader = new BufferedReader(new FileReader(diffFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("---")) {
                    diff.originalFilePath = line.substring(4).trim(); // 提取初始文件路径
                } else if (line.startsWith("+++")) {
                    diff.targetFilePath = line.substring(4).trim(); // 提取目标文件路径
                } else if (line.startsWith("-") || line.startsWith("+") || line.startsWith("@@")) {
                    // 将差异的行保存下来
                    diff.diffLines.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return diff;
    }

    public static String readFileContent(String filePath) {
        File file = new File(filePath);
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String applyDiff(String originalContent, List<String> diffLines) {
        String[] lines = originalContent.split("\n");
        StringBuilder newContent = new StringBuilder();

        int lineIndex = 0;
        int array=0;
        for (String diffLine : diffLines) {
            if (diffLine.startsWith("@@")) {
                // 解析 @@ 行，找到要修改的行号
                // 示例: @@ -13,6 +13,3 @@
                String[] parts = diffLine.split(" ");
                String originalInfo = parts[1]; // 获取原始文件的行信息
                String[] originalRange = originalInfo.substring(1).split(",");
                lineIndex = Integer.parseInt(originalRange[0]) - 1; // 获取修改的起始行号（数组从0开始）
                while(lineIndex >= array) {
                    newContent.append(lines[array]).append("\n");
                    array++;
                }
            } else if (diffLine.startsWith("-")) {
                // 删除行，跳过此行

                lineIndex++;
            } else if (diffLine.startsWith("+")) {
                // 添加行
                newContent.append(diffLine.substring(1)).append("\n");
            }
        }

        // 处理剩余的原始文件内容
        lineIndex++;
        while (lineIndex < lines.length) {
            newContent.append(lines[lineIndex]).append("\n");
            lineIndex++;
        }

        return newContent.toString();
    }

    


}
