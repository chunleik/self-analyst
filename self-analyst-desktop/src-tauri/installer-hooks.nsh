; 保存交互式升级前的入口；静默升级没有页面卸载步骤，在 PREINSTALL 保存。
Var SelfAnalystStartupBeforeInstall
Var SelfAnalystStartupCaptured
!define MUI_CUSTOMFUNCTION_GUIINIT SelfAnalystCaptureStartup

Function SelfAnalystCaptureStartup
  ${If} $SelfAnalystStartupCaptured != 1
    ReadRegStr $SelfAnalystStartupBeforeInstall HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "SelfAnalystDesktop"
    StrCpy $SelfAnalystStartupCaptured 1
  ${EndIf}
FunctionEnd

!macro NSIS_HOOK_PREINSTALL
  Call SelfAnalystCaptureStartup
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; 仅原位置升级可恢复原先启用的入口，首次安装不创建入口。
  Push $0
  Push $1
  StrCpy $1 '$\"$INSTDIR\${MAINBINARYNAME}.exe$\" --autostart'
  ${If} $SelfAnalystStartupBeforeInstall == $1
    ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "SelfAnalystDesktop"
    ${If} $0 == ""
      WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "SelfAnalystDesktop" $1
    ${EndIf}
  ${EndIf}
  Pop $1
  Pop $0
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; /UPDATE 表示资源更新；真正卸载只删除指向本安装的完整入口。
  ${If} $UpdateMode != 1
    Push $0
    Push $1
    ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "SelfAnalystDesktop"
    StrCpy $1 '$\"$INSTDIR\${MAINBINARYNAME}.exe$\" --autostart'
    ${If} $0 == $1
      DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "SelfAnalystDesktop"
    ${EndIf}
    Pop $1
    Pop $0
  ${EndIf}
!macroend
