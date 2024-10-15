package com.qiqv.demo3;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.util.List;

public class HighLight {
    public static void highlightDifferences(List<String> file1Lines, List<String> file2Lines, List<String> diffLines, JTextArea textArea1, JTextArea textArea2) throws BadLocationException {
        Highlighter highlighter1 = textArea1.getHighlighter();
        Highlighter highlighter2 = textArea2.getHighlighter();

        int pos1 = 0;  // 记录第一个文件在 JTextArea 中的偏移位置
        int pos2 = 0;  // 记录第二个文件在 JTextArea 中的偏移位置
        int lineIndex1=0;
        int lineIndex2=0;
        for (String diffLine : diffLines) {
            // 解析 diff 文件中的差异信息
            if (diffLine.startsWith("@@")) {
                String[] parts = diffLine.split(" ");
                String originalInfo1 = parts[1]; // 获取原始文件的行信息
                String[] originalRange1 = originalInfo1.substring(1).split(",");
                lineIndex1 = Integer.parseInt(originalRange1[0]); // 获取修改的起始行号（数组从0开始）
                String originalInfo2 = parts[2]; // 获取原始文件的行信息
                String[] originalRange2 = originalInfo2.substring(1).split(",");
                lineIndex2 = Integer.parseInt(originalRange2[0]); // 获取修改的起始行号（数组从0开始）
                // diff 行，解析出修改范围
                continue;
            }
            else if (diffLine.startsWith("-")) {
                // 删除的行，左边高亮红色
                int startPos = calculateStartPos(file1Lines,lineIndex1);
                int endPos = startPos + diffLine.length();
                highlighter1.addHighlight(startPos, endPos, new DefaultHighlighter.DefaultHighlightPainter(Color.RED));
                pos1 += 1;
                lineIndex1++;
                continue;
            } else if (diffLine.startsWith("+")) {

                // 新增的行，右边高亮绿色
                int startPos2 = calculateStartPos(file2Lines,lineIndex2);
                int endPos2 = startPos2 + diffLine.length();
                System.out.println("Highlighting from " + startPos2 + " to " + endPos2 + " for content: " + diffLine.length());
                highlighter2.addHighlight(startPos2, endPos2, new DefaultHighlighter.DefaultHighlightPainter(Color.GREEN));
                pos2 +=1;
                lineIndex2++;
                continue;
            }
        }

    }
    public static int calculateStartPos(List<String> lines, int lineIndex) {
        int startPos = 0;

        // 遍历 lineIndex 之前的所有行，累加每一行的长度和换行符
        for (int i = 0; i < lineIndex; i++) {
            startPos += lines.get(i).length();  // 加上该行的字符数
            startPos += 1;  // 换行符的长度，一般为 1（\n）
        }
        return startPos;  // 返回第 lineIndex 行的起始位置
    }
}
