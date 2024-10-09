package com.qiqv.demo3;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

public class MyPlugin extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            VersionManager versionManager = new VersionManager(project);
            versionManager.saveEntireProject(); // 点击按钮时保存文件
        }
    }
}
