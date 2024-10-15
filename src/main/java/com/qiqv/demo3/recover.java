package com.qiqv.demo3;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
//
//    这个类实现文件历史版本恢复
//

public class recover {
    public static void writeFile(List<String> content, String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (String line : content) {
                writer.write(line);
                writer.write("\n");  // 使用 LF 换行符
            }
            System.out.println("文件已覆盖写入 (LF 格式): " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
