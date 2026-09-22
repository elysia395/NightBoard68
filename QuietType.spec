# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['C:\\Users\\chensy\\NightBoard68-mac-inspect\\quiettype\\server\\quiettype_server.py'],
    pathex=['C:\\Users\\chensy\\NightBoard68-mac-inspect\\quiettype\\server\\vendor'],
    binaries=[],
    datas=[('C:\\Users\\chensy\\NightBoard68-mac-inspect\\quiettype\\server\\web', 'web'), ('C:\\Users\\chensy\\NightBoard68-mac-inspect\\quiettype\\server\\vendor', 'vendor')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='QuietType',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
