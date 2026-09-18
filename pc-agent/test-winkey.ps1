# NightBoard68 Agent 修饰键注入回归测试（对应 pc-agent/test-e2e.js 的按键部分）
# 前置：先运行 NightBoardAgent.exe（会抢占手机的单客户端连接，测完手机自动重连）
# 注意：LWin/RWin 释放时 Windows 会弹出开始菜单，脚本结尾自动按 Esc 关闭
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Kb {
  [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vk);
}
"@

function IsDown([int]$vk) { return (([Kb]::GetAsyncKeyState($vk) -band 0x8000) -ne 0) }

$c = New-Object System.Net.Sockets.TcpClient
$c.Connect('127.0.0.1', 6868)
$s = $c.GetStream()
$w = New-Object System.IO.StreamWriter($s)
$w.AutoFlush = $true

function Send([string]$line) {
  try { $w.WriteLine($line) } catch { Write-Host "  (连接已被新客户端抢占，提前结束)"; throw }
}

Send '{"t":"hello","v":1,"n":"WinFixTest"}'
Start-Sleep -Milliseconds 150

$script:ok = $true
$script:done = $true
function Test-Key([string]$name, [int]$hid, [int]$vk) {
  if (-not $script:done) { return }
  try {
    Send ('{"t":"kd","c":' + $hid + '}')
    Start-Sleep -Milliseconds 180
    $down = IsDown $vk
    Send ('{"t":"ku","c":' + $hid + '}')
    Start-Sleep -Milliseconds 180
    $up = -not (IsDown $vk)
    $pass = $down -and $up
    if (-not $pass) { $script:ok = $false }
    Write-Host ("{0,-12} hid=0x{1:X2} -> VK=0x{2:X2}   down={3,-5} released={4,-5}  {5}" -f `
      $name, $hid, $vk, $down, $up, $(if ($pass) { 'PASS' } else { 'FAIL' }))
  } catch { $script:done = $false; $script:ok = $false }
}

# RWin 排最前：手机被踢后约 1 秒会重连抢占，先验证唯一未测过的键
Test-Key 'RWin'      0xE7 0x5C
Test-Key 'LWin'      0xE3 0x5B
Test-Key 'RCtrl'     0xE4 0xA3
Test-Key 'RAltGr'    0xE6 0xA4

if ($script:done) {
  # Win 键释放会弹出开始菜单：连发两次 Esc 关掉
  try { Send '{"t":"kd","c":41}'; Start-Sleep -Milliseconds 60; Send '{"t":"ku","c":41}' } catch {}
  Start-Sleep -Milliseconds 250
  try { Send '{"t":"kd","c":41}'; Start-Sleep -Milliseconds 60; Send '{"t":"ku","c":41}' } catch {}
  Start-Sleep -Milliseconds 150
}
try { $c.Close() } catch {}

if ($script:ok -and $script:done) { Write-Host 'ALL PASS'; exit 0 }
else { Write-Host 'INCOMPLETE OR FAILED'; exit 1 }
