"""Writes app/src/main/res/values-<lang>/strings.xml and plurals.xml from the tables below.

Only text people read is translated; product names, key labels (Ctrl, Esc, PgUp) and the app
name stay as they are, and anything missing falls back to English. Placeholders (%1$s, %2$d)
must be kept exactly.

    py -3 tools/translations.py
"""
import os
import re

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res")

# name -> {lang: text}. Languages: hi (Hindi), es (Spanish), pt-rBR (Brazilian Portuguese),
# in (Indonesian; Android's folder name for it), zh-rCN (Simplified Chinese).
T = {
    "folders_title": dict(hi="फ़ोल्डर", es="Carpetas", pt="Pastas", id="Folder", zh="文件夹"),
    "settings_title": dict(hi="सेटिंग्स", es="Ajustes", pt="Configurações", id="Setelan", zh="设置"),
    "new_session_title": dict(hi="नया सेशन", es="Nueva sesión", pt="Nova sessão", id="Sesi baru", zh="新会话"),
    "action_open_terminal": dict(hi="टर्मिनल खोलें", es="Abrir terminal", pt="Abrir terminal", id="Buka terminal", zh="打开终端"),
    "action_run": dict(hi="चलाएँ", es="Ejecutar", pt="Executar", id="Jalankan", zh="运行"),
    "action_cancel": dict(hi="रद्द करें", es="Cancelar", pt="Cancelar", id="Batal", zh="取消"),
    "action_add": dict(hi="जोड़ें", es="Añadir", pt="Adicionar", id="Tambah", zh="添加"),
    "action_remove": dict(hi="हटाएँ", es="Quitar", pt="Remover", id="Hapus", zh="移除"),
    "action_copy": dict(hi="कॉपी करें", es="Copiar", pt="Copiar", id="Salin", zh="复制"),
    "action_copied": dict(hi="कॉपी हो गया", es="Copiado", pt="Copiado", id="Tersalin", zh="已复制"),
    "action_retry": dict(hi="फिर कोशिश करें", es="Reintentar", pt="Tentar de novo", id="Coba lagi", zh="重试"),
    "action_install": dict(hi="इंस्टॉल करें", es="Instalar", pt="Instalar", id="Pasang", zh="安装"),
    "action_restart": dict(hi="फिर से शुरू करें", es="Reiniciar", pt="Reiniciar", id="Mulai ulang", zh="重启"),
    "action_reset": dict(hi="रीसेट", es="Restablecer", pt="Redefinir", id="Setel ulang", zh="重置"),
    "action_repair": dict(hi="ठीक करें", es="Reparar", pt="Reparar", id="Perbaiki", zh="修复"),
    "action_close": dict(hi="बंद करें", es="Cerrar", pt="Fechar", id="Tutup", zh="关闭"),
    "acp_status_connecting": dict(hi="कनेक्ट हो रहा है…", es="Conectando…", pt="Conectando…", id="Menghubungkan…", zh="正在连接…"),
    "acp_thinking": dict(hi="सोच रहा है", es="Pensando", pt="Pensando", id="Berpikir", zh="思考中"),
    "acp_auth_required": dict(hi="साइन-इन ज़रूरी है", es="Hace falta iniciar sesión", pt="É preciso entrar", id="Perlu masuk", zh="需要登录"),
    "acp_auth_body": dict(
        hi="इस फ़ोल्डर में एक Shell सेशन खोलें और वहाँ एजेंट में साइन इन करें (जैसे claude, codex login या pi चलाएँ), फिर लौटकर फिर कोशिश करें पर टैप करें। साइन-इन इस सेशन के साथ साझा होता है।",
        es="Abre una sesión de Shell en esta carpeta e inicia sesión en el agente ahí (por ejemplo, ejecuta claude, codex login o pi); luego vuelve y toca Reintentar. El inicio de sesión se comparte con esta sesión.",
        pt="Abra uma sessão de Shell nesta pasta e entre no agente por lá (por exemplo, rode claude, codex login ou pi); depois volte e toque em Tentar de novo. O login é compartilhado com esta sessão.",
        id="Buka sesi Shell di folder ini dan masuk ke agen di sana (misalnya jalankan claude, codex login, atau pi), lalu kembali dan ketuk Coba lagi. Login dibagikan dengan sesi ini.",
        zh="在此文件夹中打开一个 Shell 会话并在其中登录代理（例如运行 claude、codex login 或 pi），然后返回并点按“重试”。登录状态会与此会话共享。",
    ),
    "acp_auth_methods": dict(hi="समर्थित: %1$s", es="Compatibles: %1$s", pt="Compatíveis: %1$s", id="Didukung: %1$s", zh="支持：%1$s"),
    "acp_agent_unavailable": dict(hi="एजेंट की जानकारी उपलब्ध नहीं", es="No hay datos del agente", pt="Dados do agente indisponíveis", id="Detail agen tidak tersedia", zh="无法获取代理信息"),
    "acp_restart": dict(hi="एजेंट फिर से शुरू करें", es="Reiniciar agente", pt="Reiniciar agente", id="Mulai ulang agen", zh="重启代理"),
    "acp_setting_up": dict(hi="%1$s सेट हो रहा है", es="Preparando %1$s", pt="Preparando %1$s", id="Menyiapkan %1$s", zh="正在设置 %1$s"),
    "acp_first_run_note": dict(
        hi="पहली बार एजेंट इस डिवाइस के Linux वातावरण में डाउनलोड होता है। उसके बाद सेशन कुछ ही सेकंड में शुरू होते हैं।",
        es="La primera vez, el agente se descarga en el entorno Linux de este dispositivo. Después, las sesiones empiezan en segundos.",
        pt="Na primeira vez, o agente é baixado para o ambiente Linux deste aparelho. Depois disso, as sessões começam em segundos.",
        id="Pada awal penggunaan, agen diunduh ke lingkungan Linux perangkat ini. Setelah itu, sesi dimulai dalam hitungan detik.",
        zh="首次运行时会把代理下载到本设备的 Linux 环境中。之后，会话几秒钟就能启动。",
    ),
    "acp_ready_title": dict(hi="%1$s तैयार है", es="%1$s está listo", pt="%1$s está pronto", id="%1$s siap", zh="%1$s 已就绪"),
    "acp_working": dict(hi="काम कर रहा है", es="Trabajando", pt="Trabalhando", id="Sedang bekerja", zh="工作中"),
    "acp_phase_ready": dict(hi="तैयार", es="Listo", pt="Pronto", id="Siap", zh="就绪"),
    "acp_phase_signin": dict(hi="साइन-इन ज़रूरी", es="Falta iniciar sesión", pt="Login necessário", id="Perlu masuk", zh="需要登录"),
    "acp_phase_stopped": dict(hi="रुका हुआ", es="Detenido", pt="Parado", id="Berhenti", zh="已停止"),
    "acp_phase_starting": dict(hi="शुरू हो रहा है", es="Iniciando", pt="Iniciando", id="Memulai", zh="正在启动"),
    "acp_thought": dict(hi="सोच", es="Razonamiento", pt="Raciocínio", id="Pemikiran", zh="思考"),
    "acp_plan": dict(hi="योजना", es="Plan", pt="Plano", id="Rencana", zh="计划"),
    "acp_settings": dict(hi="मॉडल और रीज़निंग", es="Modelo y razonamiento", pt="Modelo e raciocínio", id="Model dan penalaran", zh="模型与推理"),
    "acp_setting_on": dict(hi="चालू", es="Sí", pt="Ligado", id="Aktif", zh="开"),
    "acp_setting_off": dict(hi="बंद", es="No", pt="Desligado", id="Mati", zh="关"),
    "acp_setting_search": dict(hi="मॉडल खोजें", es="Buscar modelos", pt="Buscar modelos", id="Cari model", zh="搜索模型"),
    "acp_setting_recent": dict(hi="हाल के", es="Recientes", pt="Recentes", id="Terbaru", zh="最近使用"),
    "acp_setting_other": dict(hi="अन्य", es="Otros", pt="Outros", id="Lainnya", zh="其他"),
    "acp_setting_no_match": dict(hi="कोई मेल नहीं", es="Sin resultados", pt="Nenhum resultado", id="Tidak ada yang cocok", zh="无匹配项"),
    "acp_stop": dict(hi="रोकें", es="Detener", pt="Parar", id="Hentikan", zh="停止"),
    "acp_remove": dict(hi="हटाएँ", es="Quitar", pt="Remover", id="Hapus", zh="移除"),
    "acp_message_hint": dict(hi="%1$s को संदेश भेजें", es="Mensaje para %1$s", pt="Mensagem para %1$s", id="Kirim pesan ke %1$s", zh="给 %1$s 发消息"),
    "acp_pasted_file_meta": dict(hi="%1$d पंक्तियाँ · %2$s अक्षर", es="%1$d líneas · %2$s caracteres", pt="%1$d linhas · %2$s caracteres", id="%1$d baris · %2$s karakter", zh="%1$d 行 · %2$s 个字符"),
    "acp_cmd_resume": dict(hi="पिछला सेशन फिर खोलें", es="Reabrir una sesión anterior", pt="Reabrir uma sessão anterior", id="Buka lagi sesi sebelumnya", zh="重新打开之前的会话"),
    "acp_cmd_new": dict(hi="नया सेशन", es="Nueva sesión", pt="Nova sessão", id="Sesi baru", zh="新会话"),
    "acp_history": dict(hi="पिछले सेशन", es="Sesiones anteriores", pt="Sessões anteriores", id="Sesi sebelumnya", zh="之前的会话"),
    "acp_history_title": dict(hi="इस फ़ोल्डर के सेशन", es="Sesiones en esta carpeta", pt="Sessões nesta pasta", id="Sesi di folder ini", zh="此文件夹中的会话"),
    "acp_history_empty": dict(hi="अभी कोई पिछला सेशन नहीं।", es="Todavía no hay sesiones anteriores.", pt="Ainda não há sessões anteriores.", id="Belum ada sesi sebelumnya.", zh="还没有之前的会话。"),
    "acp_history_untitled": dict(hi="बिना नाम का सेशन", es="Sesión sin título", pt="Sessão sem título", id="Sesi tanpa judul", zh="未命名会话"),
    "acp_history_current": dict(hi="अभी खुला", es="abierta ahora", pt="aberta agora", id="sedang dibuka", zh="当前打开"),
    "acp_restoring": dict(hi="सेशन फिर खुल रहा है…", es="Reabriendo la sesión…", pt="Reabrindo a sessão…", id="Membuka lagi sesi…", zh="正在重新打开会话…"),
    "files_title": dict(hi="फ़ाइलें", es="Archivos", pt="Arquivos", id="File", zh="文件"),
    "files_toggle": dict(hi="फ़ाइलें दिखाएँ", es="Mostrar archivos", pt="Mostrar arquivos", id="Tampilkan file", zh="显示文件"),
    "files_refresh": dict(hi="रीफ़्रेश करें", es="Actualizar", pt="Atualizar", id="Muat ulang", zh="刷新"),
    "files_no_path": dict(hi="इस फ़ोल्डर का डिवाइस पर कोई पाथ नहीं है, इसलिए इसकी फ़ाइलें नहीं दिखाई जा सकतीं।", es="Esta carpeta no tiene una ruta en el dispositivo, así que no se pueden mostrar sus archivos.", pt="Esta pasta não tem um caminho no aparelho, então não é possível listar os arquivos.", id="Folder ini tidak punya jalur di perangkat, jadi file-nya tidak bisa ditampilkan.", zh="此文件夹在设备上没有路径，因此无法列出其中的文件。"),
    "files_empty": dict(hi="यह फ़ोल्डर खाली है।", es="Esta carpeta está vacía.", pt="Esta pasta está vazia.", id="Folder ini kosong.", zh="此文件夹为空。"),
    "files_unreadable": dict(hi="यह फ़ाइल पढ़ी नहीं जा सकती।", es="No se puede leer este archivo.", pt="Não é possível ler este arquivo.", id="File ini tidak bisa dibaca.", zh="无法读取此文件。"),
    "files_too_large": dict(hi="यह फ़ाइल यहाँ खोलने के लिए बहुत बड़ी है (4 MB से ज़्यादा)।", es="Este archivo es demasiado grande para abrirlo aquí (más de 4 MB).", pt="Este arquivo é grande demais para abrir aqui (mais de 4 MB).", id="File ini terlalu besar untuk dibuka di sini (lebih dari 4 MB).", zh="此文件太大，无法在这里打开（超过 4 MB）。"),
    "files_binary": dict(hi="यह बाइनरी फ़ाइल है, इसलिए इसे टेक्स्ट के रूप में नहीं दिखाया जा सकता।", es="Es un archivo binario, así que no hay texto que mostrar.", pt="É um arquivo binário, então não há texto para mostrar.", id="Ini file biner, jadi tidak ada teks yang bisa ditampilkan.", zh="这是二进制文件，没有可显示的文本。"),
    "files_save": dict(hi="सेव करें", es="Guardar", pt="Salvar", id="Simpan", zh="保存"),
    "files_save_failed": dict(hi="सेव नहीं हो सका: %1$s", es="No se pudo guardar: %1$s", pt="Não foi possível salvar: %1$s", id="Gagal menyimpan: %1$s", zh="无法保存：%1$s"),
    "files_search": dict(hi="खोजें", es="Buscar", pt="Localizar", id="Cari", zh="查找"),
    "files_wrap": dict(hi="पंक्तियाँ लपेटें", es="Ajustar líneas", pt="Quebrar linhas", id="Bungkus baris", zh="自动换行"),
    "files_undo": dict(hi="पूर्ववत करें", es="Deshacer", pt="Desfazer", id="Urungkan", zh="撤销"),
    "files_redo": dict(hi="फिर से करें", es="Rehacer", pt="Refazer", id="Ulangi", zh="重做"),
    "files_read_only": dict(hi="केवल पढ़ने के लिए", es="solo lectura", pt="somente leitura", id="hanya baca", zh="只读"),
    "files_cursor": dict(hi="पंक्ति %1$d, कॉलम %2$d", es="Lín %1$d, Col %2$d", pt="Lin %1$d, Col %2$d", id="Brs %1$d, Kol %2$d", zh="行 %1$d，列 %2$d"),
    "files_unsaved_title": dict(hi="बदलाव छोड़ दें?", es="¿Descartar los cambios?", pt="Descartar as alterações?", id="Buang perubahan?", zh="放弃更改？"),
    "files_unsaved_body": dict(hi="%1$s में ऐसे बदलाव हैं जो सेव नहीं हुए।", es="%1$s tiene cambios sin guardar.", pt="%1$s tem alterações não salvas.", id="%1$s punya perubahan yang belum disimpan.", zh="%1$s 有未保存的更改。"),
    "files_discard": dict(hi="छोड़ दें", es="Descartar", pt="Descartar", id="Buang", zh="放弃"),
    "files_delete": dict(hi="मिटाएँ", es="Eliminar", pt="Excluir", id="Hapus", zh="删除"),
    "files_new_file": dict(hi="नई फ़ाइल", es="Nuevo archivo", pt="Novo arquivo", id="File baru", zh="新建文件"),
    "files_new_folder": dict(hi="नया फ़ोल्डर", es="Nueva carpeta", pt="Nova pasta", id="Folder baru", zh="新建文件夹"),
    "files_rename": dict(hi="नाम बदलें", es="Cambiar nombre", pt="Renomear", id="Ganti nama", zh="重命名"),
    "files_rename_title": dict(hi="%1$s का नाम बदलें", es="Cambiar nombre de %1$s", pt="Renomear %1$s", id="Ganti nama %1$s", zh="重命名 %1$s"),
    "files_create": dict(hi="बनाएँ", es="Crear", pt="Criar", id="Buat", zh="创建"),
    "files_move": dict(hi="यहाँ ले जाएँ…", es="Mover a…", pt="Mover para…", id="Pindahkan ke…", zh="移动到…"),
    "files_move_title": dict(hi="%1$s को यहाँ ले जाएँ", es="Mover %1$s a", pt="Mover %1$s para", id="Pindahkan %1$s ke", zh="将 %1$s 移动到"),
    "files_move_root": dict(hi="%1$s (सबसे ऊपर)", es="%1$s (nivel superior)", pt="%1$s (nível superior)", id="%1$s (tingkat teratas)", zh="%1$s（顶层）"),
    "files_name_empty": dict(hi="एक नाम लिखें।", es="Escribe un nombre.", pt="Digite um nome.", id="Masukkan nama.", zh="请输入名称。"),
    "files_name_invalid": dict(hi="नाम में / नहीं हो सकता, और नाम . या .. नहीं हो सकता", es="Un nombre no puede contener / ni ser . o ..", pt="Um nome não pode conter / nem ser . ou ..", id="Nama tidak boleh berisi / atau berupa . atau ..", zh="名称不能包含 /，也不能是 . 或 .."),
    "files_delete_title": dict(hi="%1$s मिटाएँ?", es="¿Eliminar %1$s?", pt="Excluir %1$s?", id="Hapus %1$s?", zh="删除 %1$s？"),
    "files_delete_folder_title": dict(hi="फ़ोल्डर %1$s मिटाएँ?", es="¿Eliminar la carpeta %1$s?", pt="Excluir a pasta %1$s?", id="Hapus folder %1$s?", zh="删除文件夹 %1$s？"),
    "files_delete_body": dict(hi="फ़ाइल आपके डिवाइस के फ़ोल्डर से हटा दी जाएगी। इसे वापस नहीं लाया जा सकता।", es="El archivo se elimina de la carpeta de tu dispositivo. No se puede deshacer.", pt="O arquivo é removido da pasta no seu aparelho. Não dá para desfazer.", id="File akan dihapus dari folder di perangkat Anda. Tindakan ini tidak bisa dibatalkan.", zh="该文件将从设备上的文件夹中删除，且无法撤销。"),
    "files_delete_folder_body": dict(hi="फ़ोल्डर और उसके अंदर की हर चीज़ आपके डिवाइस से हटा दी जाएगी। इसे वापस नहीं लाया जा सकता।", es="La carpeta y todo su contenido se eliminan de tu dispositivo. No se puede deshacer.", pt="A pasta e tudo o que há nela são removidos do seu aparelho. Não dá para desfazer.", id="Folder dan semua isinya akan dihapus dari perangkat Anda. Tindakan ini tidak bisa dibatalkan.", zh="该文件夹及其中的所有内容都将从设备中删除，且无法撤销。"),
    "files_delete_failed": dict(hi="मिटाया नहीं जा सका: %1$s", es="No se pudo eliminar: %1$s", pt="Não foi possível excluir: %1$s", id="Gagal menghapus: %1$s", zh="无法删除：%1$s"),
    "files_delete_denied": dict(hi="फ़ाइल हटाई नहीं जा सकी (शायद वह केवल पढ़ने के लिए है या इस्तेमाल में है)।", es="no se pudo eliminar el archivo (puede que sea de solo lectura o esté en uso).", pt="não foi possível remover o arquivo (ele pode ser somente leitura ou estar em uso).", id="file tidak bisa dihapus (mungkin hanya-baca atau sedang digunakan).", zh="无法删除该文件（它可能是只读的或正在使用中）。"),
    "terminal_find": dict(hi="टर्मिनल में खोजें", es="Buscar en la terminal", pt="Buscar no terminal", id="Cari di terminal", zh="在终端中搜索"),
    "terminal_find_hint": dict(hi="टर्मिनल में खोजें", es="Buscar en la terminal", pt="Buscar no terminal", id="Cari di terminal", zh="在终端中查找"),
    "terminal_find_none": dict(hi="कोई मेल नहीं", es="Sin resultados", pt="Nenhum resultado", id="Tidak ditemukan", zh="无匹配项"),
    "terminal_find_count": dict(hi="%2$d में से %1$d", es="%1$d de %2$d", pt="%1$d de %2$d", id="%1$d dari %2$d", zh="第 %1$d 个，共 %2$d 个"),
    "terminal_find_previous": dict(hi="पिछला मेल", es="Resultado anterior", pt="Resultado anterior", id="Hasil sebelumnya", zh="上一个"),
    "terminal_find_next": dict(hi="अगला मेल", es="Resultado siguiente", pt="Próximo resultado", id="Hasil berikutnya", zh="下一个"),
    "settings_terminal": dict(hi="टर्मिनल के रंग", es="Colores de la terminal", pt="Cores do terminal", id="Warna terminal", zh="终端配色"),
    "channel_done": dict(hi="एजेंट ने काम पूरा किया", es="Agente terminado", pt="Agente terminou", id="Agen selesai", zh="代理已完成"),
    "channel_done_desc": dict(hi="जब आप कहीं और हों और कोई एजेंट या लंबा कमांड पूरा हो जाए", es="Cuando un agente o un comando largo termina mientras estás en otra cosa", pt="Quando um agente ou um comando longo termina enquanto você está em outro app", id="Saat agen atau perintah panjang selesai ketika Anda di tempat lain", zh="当你不在此应用时，代理或长时间运行的命令完成"),
    "channel_attention": dict(hi="आपका ध्यान चाहिए", es="Necesita tu atención", pt="Precisa da sua atenção", id="Perlu perhatian Anda", zh="需要你处理"),
    "channel_attention_desc": dict(hi="जब कोई एजेंट अनुमति माँगे या टर्मिनल की घंटी बजे", es="Cuando un agente pide permiso o la terminal hace sonar la campana", pt="Quando um agente pede permissão ou o terminal toca o alerta", id="Saat agen meminta izin atau terminal membunyikan bel", zh="当代理请求权限或终端发出提示音时"),
    "channel_bubble": dict(hi="बबल", es="Burbuja", pt="Balão", id="Gelembung", zh="气泡"),
    "channel_bubble_desc": dict(hi="दूसरे ऐप्स के ऊपर तैरता TermFold", es="TermFold flotando sobre otras apps", pt="TermFold flutuando sobre outros apps", id="TermFold melayang di atas aplikasi lain", zh="悬浮在其他应用上方的 TermFold"),
    "channel_running": dict(hi="चल रहे सेशन", es="Sesiones en curso", pt="Sessões em execução", id="Sesi berjalan", zh="正在运行的会话"),
    "channel_running_desc": dict(hi="एजेंट और शेल को बैकग्राउंड में चलता रखता है", es="Mantiene los agentes y shells funcionando en segundo plano", pt="Mantém agentes e shells rodando em segundo plano", id="Menjaga agen dan shell tetap berjalan di latar belakang", zh="让代理和 Shell 在后台持续运行"),
    "bubble_you": dict(hi="आप", es="Tú", pt="Você", id="Anda", zh="你"),
    "notify_done_title": dict(hi="%1$s ने %2$s में काम पूरा किया", es="%1$s terminó en %2$s", pt="%1$s terminou em %2$s", id="%1$s selesai di %2$s", zh="%1$s 已在 %2$s 中完成"),
    "notify_done_body": dict(hi="एजेंट का काम पूरा हुआ। उसने क्या किया, देखने के लिए टैप करें।", es="El agente terminó. Toca para ver lo que hizo.", pt="O agente terminou. Toque para ver o que ele fez.", id="Agen sudah selesai. Ketuk untuk melihat hasilnya.", zh="代理已完成。点按查看它做了什么。"),
    "notify_permission_title": dict(hi="%1$s को अनुमति चाहिए", es="%1$s necesita permiso", pt="%1$s precisa de permissão", id="%1$s perlu izin", zh="%1$s 需要权限"),
    "notify_permission_body": dict(hi="%1$s में आपका इंतज़ार कर रहा है", es="Esperándote en %1$s", pt="Esperando por você em %1$s", id="Menunggu Anda di %1$s", zh="正在 %1$s 中等你"),
    "notify_shell_title": dict(hi="%2$s में %1$s पूरा हुआ", es="%1$s en %2$s terminó", pt="%1$s em %2$s terminou", id="%1$s di %2$s selesai", zh="%2$s 中的 %1$s 已完成"),
    "notify_shell_body": dict(hi="कमांड शांत हो गया है। देखने के लिए टैप करें।", es="El comando ya no produce salida. Toca para verlo.", pt="O comando parou de produzir saída. Toque para ver.", id="Perintah sudah diam. Ketuk untuk melihat.", zh="命令已停止输出。点按查看。"),
    "notify_bell_title": dict(hi="%1$s को आपकी ज़रूरत है", es="%1$s te necesita", pt="%1$s precisa de você", id="%1$s membutuhkan Anda", zh="%1$s 需要你"),
    "notify_bell_body": dict(hi="टर्मिनल ने घंटी बजाई।", es="La terminal hizo sonar la campana.", pt="O terminal tocou o alerta.", id="Terminal membunyikan bel.", zh="终端发出了提示音。"),
    "running_idle": dict(hi="आपका इंतज़ार कर रहा है", es="Esperándote", pt="Esperando por você", id="Menunggu Anda", zh="等待你的操作"),
    "settings_background": dict(hi="जब आप कहीं और हों", es="Mientras estás en otra cosa", pt="Enquanto você está em outro app", id="Saat Anda di tempat lain", zh="当你离开时"),
    "settings_bubble": dict(hi="बबल के रूप में तैराएँ", es="Flotar como burbuja", pt="Flutuar como balão", id="Melayang sebagai gelembung", zh="以气泡悬浮"),
    "settings_bubble_desc": dict(hi="किसी फ़ोल्डर या सेशन से ऐप छोड़ने पर यह दूसरे ऐप्स के ऊपर तैरता रहता है, वहीं खुलता है जहाँ आप थे।", es="Al salir de la app desde una carpeta o sesión, queda flotando sobre otras apps y se abre donde estabas.", pt="Ao sair do app a partir de uma pasta ou sessão, ele fica flutuando sobre outros apps e abre onde você estava.", id="Saat keluar dari aplikasi dari folder atau sesi, aplikasi tetap melayang di atas aplikasi lain dan terbuka di tempat Anda tadi.", zh="从文件夹或会话离开应用时，它会悬浮在其他应用上方，并在你离开的地方打开。"),
    "settings_bubble_blocked": dict(hi="Android अभी TermFold को बबल नहीं बनने दे रहा।", es="Android todavía no permite que TermFold use burbujas.", pt="O Android ainda não permite que o TermFold use balões.", id="Android belum mengizinkan TermFold menjadi gelembung.", zh="Android 暂未允许 TermFold 以气泡显示。"),
    "settings_bubble_allow": dict(hi="बबल की अनुमति दें", es="Permitir burbujas", pt="Permitir balões", id="Izinkan gelembung", zh="允许气泡"),
    "settings_notify": dict(hi="काम पूरा होने पर सूचना दें", es="Avisar cuando termine el trabajo", pt="Avisar quando o trabalho terminar", id="Beri tahu saat pekerjaan selesai", zh="工作完成时通知"),
    "settings_notify_desc": dict(hi="जब कोई एजेंट काम पूरा करे या अनुमति माँगे, या शेल में लंबा कमांड खत्म हो, तब सूचना।", es="Un aviso cuando un agente termina o pide permiso, o cuando acaba un comando largo en un shell.", pt="Um aviso quando um agente termina ou pede permissão, ou quando um comando longo termina num shell.", id="Peringatan saat agen selesai atau meminta izin, atau saat perintah panjang di shell berakhir.", zh="当代理完成或请求权限，或 Shell 中的长命令结束时提醒你。"),
    "settings_notify_blocked": dict(hi="TermFold के लिए सूचनाएँ बंद हैं।", es="Las notificaciones de TermFold están desactivadas.", pt="As notificações do TermFold estão desativadas.", id="Notifikasi untuk TermFold dimatikan.", zh="TermFold 的通知已关闭。"),
    "settings_notify_allow": dict(hi="चालू करें", es="Activar", pt="Ativar", id="Aktifkan", zh="开启"),
    "settings_battery": dict(hi="बैटरी सीमाओं के बिना चलाएँ", es="Sin límites de batería", pt="Sem limites de bateria", id="Jalankan tanpa batasan baterai", zh="不受电池限制运行"),
    "settings_battery_desc": dict(hi="एजेंट बैकग्राउंड में काम करते समय Android को TermFold रोकने या बंद करने से रोकता है। Lenovo, Xiaomi, Samsung जैसे फ़ोनों पर सुझाया जाता है।", es="Evita que Android pause o cierre TermFold mientras los agentes trabajan en segundo plano. Recomendado en Lenovo, Xiaomi, Samsung y similares.", pt="Impede que o Android pause ou feche o TermFold enquanto os agentes trabalham em segundo plano. Recomendado em Lenovo, Xiaomi, Samsung e parecidos.", id="Mencegah Android menjeda atau menutup TermFold saat agen bekerja di latar belakang. Disarankan untuk Lenovo, Xiaomi, Samsung, dan sejenisnya.", zh="防止 Android 在代理于后台工作时暂停或关闭 TermFold。建议在联想、小米、三星等设备上开启。"),
    "settings_battery_on": dict(hi="TermFold बैटरी ऑप्टिमाइज़ेशन से छूट प्राप्त है।", es="TermFold está excluido de la optimización de batería.", pt="O TermFold está fora da otimização de bateria.", id="TermFold dikecualikan dari pengoptimalan baterai.", zh="TermFold 已不受电池优化限制。"),
    "settings_battery_off": dict(hi="स्क्रीन बंद होने पर Android अब भी TermFold को रोक सकता है।", es="Android aún puede pausar TermFold con la pantalla apagada.", pt="O Android ainda pode pausar o TermFold com a tela desligada.", id="Android masih bisa menjeda TermFold saat layar mati.", zh="屏幕关闭时，Android 仍可能暂停 TermFold。"),
    "settings_battery_allow": dict(hi="अनुमति दें", es="Permitir", pt="Permitir", id="Izinkan", zh="允许"),
    "acp_error_title": dict(hi="एजेंट में त्रुटि", es="Error del agente", pt="Erro do agente", id="Kesalahan agen", zh="代理出错"),
    "acp_permission_title": dict(hi="एजेंट को आपकी मंज़ूरी चाहिए", es="El agente necesita tu aprobación", pt="O agente precisa da sua aprovação", id="Agen memerlukan persetujuan Anda", zh="代理需要你的批准"),
    "acp_attach_image": dict(hi="इमेज जोड़ें", es="Adjuntar imagen", pt="Anexar imagem", id="Lampirkan gambar", zh="附加图片"),
    "acp_agent_label": dict(hi="ACP एजेंट", es="Agente ACP", pt="Agente ACP", id="Agen ACP", zh="ACP 代理"),
    "acp_registry_loading": dict(hi="एजेंट लोड हो रहे हैं…", es="Cargando agentes…", pt="Carregando agentes…", id="Memuat agen…", zh="正在加载代理…"),
    "acp_registry_offline": dict(hi="ACP रजिस्ट्री लोड नहीं हो सकी। अपना कनेक्शन जाँचें।", es="No se pudo cargar el registro de ACP. Revisa tu conexión.", pt="Não foi possível carregar o registro ACP. Verifique sua conexão.", id="Registri ACP tidak bisa dimuat. Periksa koneksi Anda.", zh="无法加载 ACP 注册表。请检查网络连接。"),
    "acp_session_hint": dict(hi="शेल की जगह एक नेटिव एजेंट इंटरफ़ेस के रूप में खुलता है", es="Se abre como una interfaz nativa del agente en lugar de un shell", pt="Abre como uma interface nativa do agente em vez de um shell", id="Terbuka sebagai antarmuka agen native, bukan shell", zh="以原生代理界面打开，而不是 Shell"),
    "empty_folders": dict(hi="अभी कोई फ़ोल्डर नहीं।", es="Aún no hay carpetas.", pt="Ainda não há pastas.", id="Belum ada folder.", zh="还没有文件夹。"),
    "empty_folders_hint": dict(hi="फ़ोल्डर चुनने के लिए + का इस्तेमाल करें।", es="Usa + para elegir una carpeta.", pt="Use + para escolher uma pasta.", id="Gunakan + untuk memilih folder.", zh="点按 + 选择一个文件夹。"),
    "empty_sessions": dict(hi="अभी कोई सेशन नहीं।", es="Aún no hay sesiones.", pt="Ainda não há sessões.", id="Belum ada sesi.", zh="还没有会话。"),
    "empty_sessions_hint": dict(hi="एक जोड़ने के लिए + का इस्तेमाल करें।", es="Usa + para añadir una.", pt="Use + para adicionar uma.", id="Gunakan + untuk menambahkan.", zh="点按 + 添加一个。"),
    "terminal_placeholder": dict(hi="कमांड लिखें…", es="Escribe un comando…", pt="Digite um comando…", id="Ketik perintah…", zh="输入命令…"),
    "terminal_empty": dict(hi="आउटपुट देखने के लिए कोई कमांड चलाएँ।", es="Ejecuta un comando para ver la salida aquí.", pt="Execute um comando para ver a saída aqui.", id="Jalankan perintah untuk melihat keluarannya di sini.", zh="运行命令后，输出会显示在这里。"),
    "terminal_exited": dict(hi="शेल बंद हो गया", es="El shell terminó", pt="O shell foi encerrado", id="Shell berhenti", zh="Shell 已退出"),
    "cd_name": dict(hi="नाम", es="Nombre", pt="Nome", id="Nama", zh="名称"),
    "cd_back": dict(hi="वापस", es="Atrás", pt="Voltar", id="Kembali", zh="返回"),
    "cd_search": dict(hi="खोजें", es="Buscar", pt="Buscar", id="Cari", zh="搜索"),
    "cd_more": dict(hi="और विकल्प", es="Más opciones", pt="Mais opções", id="Opsi lainnya", zh="更多选项"),
    "cd_add_folder": dict(hi="फ़ोल्डर जोड़ें", es="Añadir carpeta", pt="Adicionar pasta", id="Tambah folder", zh="添加文件夹"),
    "cd_add_session": dict(hi="सेशन जोड़ें", es="Añadir sesión", pt="Adicionar sessão", id="Tambah sesi", zh="添加会话"),
    "cd_send": dict(hi="भेजें", es="Enviar", pt="Enviar", id="Kirim", zh="发送"),
    "cd_expand": dict(hi="टर्मिनल खोलें", es="Abrir terminal", pt="Abrir terminal", id="Buka terminal", zh="打开终端"),
    "cd_home": dict(hi="फ़ोल्डर", es="Carpetas", pt="Pastas", id="Folder", zh="文件夹"),
    "cd_settings": dict(hi="सेटिंग्स", es="Ajustes", pt="Configurações", id="Setelan", zh="设置"),
    "cd_session": dict(hi="सेशन", es="Sesiones", pt="Sessões", id="Sesi", zh="会话"),
    "cd_clear": dict(hi="साफ़ करें", es="Borrar", pt="Limpar", id="Bersihkan", zh="清除"),
    "status_ready": dict(hi="तैयार", es="Listo", pt="Pronto", id="Siap", zh="就绪"),
    "status_running": dict(hi="चल रहा है…", es="Ejecutando…", pt="Executando…", id="Berjalan…", zh="运行中…"),
    "status_failed": dict(hi="विफल", es="Error", pt="Falhou", id="Gagal", zh="失败"),
    "provision_title": dict(hi="Linux सेट हो रहा है", es="Preparando Linux", pt="Preparando o Linux", id="Menyiapkan Linux", zh="正在设置 Linux"),
    "provision_body": dict(hi="%1$s को इस ऐप में खोला जा रहा है। यह एक बार होता है — इसके बाद apt से जो भी इंस्टॉल करेंगे, वह रहेगा।", es="Desempaquetando %1$s en esta app. Solo ocurre una vez; todo lo que instales después con apt se conserva.", pt="Descompactando %1$s neste app. Isso acontece uma vez só; tudo o que você instalar depois com apt fica guardado.", id="Membongkar %1$s ke aplikasi ini. Hanya sekali; semua yang Anda pasang dengan apt setelahnya akan tetap tersimpan.", zh="正在把 %1$s 解压到此应用中。这只需一次，之后用 apt 安装的所有内容都会保留。"),
    "provision_failed": dict(hi="सेटअप विफल रहा", es="Falló la configuración", pt="A configuração falhou", id="Penyiapan gagal", zh="设置失败"),
    "key_paste": dict(hi="पेस्ट", es="Pegar", pt="Colar", id="Tempel", zh="粘贴"),
    "key_image": dict(hi="इमेज", es="Imagen", pt="Imagem", id="Gambar", zh="图片"),
    "shell_title": dict(hi="Linux वातावरण", es="Entorno Linux", pt="Ambiente Linux", id="Lingkungan Linux", zh="Linux 环境"),
    "shell_ready": dict(hi="तैयार", es="Listo", pt="Pronto", id="Siap", zh="就绪"),
    "shell_not_ready": dict(hi="अभी सेट नहीं हुआ", es="Aún no configurado", pt="Ainda não configurado", id="Belum disiapkan", zh="尚未设置"),
    "shell_arch_label": dict(hi="आर्किटेक्चर", es="Arquitectura", pt="Arquitetura", id="Arsitektur", zh="架构"),
    "shell_size_label": dict(hi="डिस्क पर आकार", es="Tamaño en disco", pt="Tamanho em disco", id="Ukuran di disk", zh="占用空间"),
    "shell_size_calculating": dict(hi="गणना हो रही है…", es="Calculando…", pt="Calculando…", id="Menghitung…", zh="正在计算…"),
    "shell_body": dict(hi="इस ऐप के अंदर एक पूरा Ubuntu वातावरण चलता है। टर्मिनल, apt और आपके चलाए एजेंट सब एक ही स्थायी सिस्टम साझा करते हैं — और कुछ इंस्टॉल करने की ज़रूरत नहीं।", es="Dentro de esta app funciona un entorno Ubuntu completo. La terminal, apt y los agentes que abras comparten un mismo sistema persistente; no hace falta instalar nada más.", pt="Um ambiente Ubuntu completo roda dentro deste app. O terminal, o apt e os agentes que você abrir compartilham um único sistema persistente; não é preciso instalar mais nada.", id="Lingkungan Ubuntu lengkap berjalan di dalam aplikasi ini. Terminal, apt, dan agen yang Anda jalankan berbagi satu sistem yang tetap; tidak perlu memasang apa pun lagi.", zh="此应用内运行着完整的 Ubuntu 环境。终端、apt 以及你启动的代理共用同一个持久系统，无需再安装其他东西。"),
    "shell_unsupported": dict(hi="साथ दी गई Linux इमेज इस डिवाइस के CPU आर्किटेक्चर से मेल नहीं खाती। यहाँ सेशन शुरू नहीं हो सकते।", es="La imagen de Linux incluida no coincide con la arquitectura de CPU de este dispositivo. Aquí no se pueden iniciar sesiones.", pt="A imagem Linux incluída não corresponde à arquitetura de CPU deste aparelho. Não é possível iniciar sessões aqui.", id="Image Linux bawaan tidak cocok dengan arsitektur CPU perangkat ini. Sesi tidak bisa dimulai di sini.", zh="内置的 Linux 镜像与本设备的 CPU 架构不匹配，无法在此启动会话。"),
    "sessions_header": dict(hi="सेशन", es="Sesiones", pt="Sessões", id="Sesi", zh="会话"),
    "session_preset_custom": dict(hi="कस्टम", es="Personalizado", pt="Personalizado", id="Kustom", zh="自定义"),
    "custom_command": dict(hi="कमांड", es="Comando", pt="Comando", id="Perintah", zh="命令"),
    "unmapped_path_title": dict(hi="इस फ़ोल्डर का कोई सिस्टम पाथ नहीं है", es="Esta carpeta no tiene una ruta del sistema", pt="Esta pasta não tem um caminho no sistema", id="Folder ini tidak punya jalur sistem", zh="此文件夹没有系统路径"),
    "unmapped_path_body": dict(hi="टर्मिनल सिर्फ़ असली स्टोरेज वाले फ़ोल्डर खोल सकता है। यह फ़ोल्डर हटाएँ और डिवाइस स्टोरेज से कोई फ़ोल्डर चुनें।", es="La terminal solo puede abrir carpetas del almacenamiento real. Quita esta carpeta y elige una del almacenamiento del dispositivo.", pt="O terminal só abre pastas do armazenamento real. Remova esta pasta e escolha uma do armazenamento do aparelho.", id="Terminal hanya bisa membuka folder dari penyimpanan nyata. Hapus folder ini dan pilih folder dari penyimpanan perangkat.", zh="终端只能打开真实存储中的文件夹。请移除此文件夹，并从设备存储中选择一个。"),
    "about_title": dict(hi="परिचय", es="Acerca de", pt="Sobre", id="Tentang", zh="关于"),
    "about_body": dict(hi="TermFold ऐप के अंदर एक असली Linux वातावरण चलाता है और उसे आपके चुने फ़ोल्डरों में खोलता है, ताकि एजेंट और शेल टूल सही प्रोजेक्ट में शुरू हों।", es="TermFold ejecuta un entorno Linux real dentro de la app y lo abre en las carpetas que elijas, para que los agentes y las herramientas de shell empiecen en el proyecto correcto.", pt="O TermFold roda um ambiente Linux de verdade dentro do app e o abre nas pastas que você escolher, para que agentes e ferramentas de shell comecem no projeto certo.", id="TermFold menjalankan lingkungan Linux sungguhan di dalam aplikasi dan membukanya di folder yang Anda pilih, sehingga agen dan alat shell dimulai di proyek yang tepat.", zh="TermFold 在应用内运行真正的 Linux 环境，并在你选择的文件夹中打开，让代理和 Shell 工具直接在正确的项目里启动。"),
    "version_label": dict(hi="संस्करण", es="Versión", pt="Versão", id="Versi", zh="版本"),
}

PLURALS = {
    "running_title": dict(
        hi=dict(one="%d सेशन चल रहा है", other="%d सेशन चल रहे हैं"),
        es=dict(one="%d sesión en curso", other="%d sesiones en curso"),
        pt=dict(one="%d sessão em execução", other="%d sessões em execução"),
        id=dict(other="%d sesi berjalan"),
        zh=dict(other="%d 个会话正在运行"),
    ),
    "running_working": dict(
        hi=dict(one="%d अभी काम कर रहा है", other="%d अभी काम कर रहे हैं"),
        es=dict(one="%d trabajando ahora", other="%d trabajando ahora"),
        pt=dict(one="%d trabalhando agora", other="%d trabalhando agora"),
        id=dict(other="%d sedang bekerja"),
        zh=dict(other="%d 个正在工作"),
    ),
}

FOLDERS = {"hi": "values-hi", "es": "values-es", "pt": "values-pt-rBR", "id": "values-in", "zh": "values-zh-rCN"}


def esc(text):
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return text.replace("'", "\\'").replace('"', '\\"')


def placeholders(text):
    return sorted(re.findall(r"%\d\$[sd]", text))


def main():
    english = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', open(os.path.join(RES, "values", "strings.xml"), encoding="utf-8").read()))
    for name, langs in T.items():
        assert name in english, f"unknown string {name}"
        for lang, text in langs.items():
            assert placeholders(text) == placeholders(english[name]), f"{name}/{lang}: placeholders differ"
    for lang, folder in FOLDERS.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Generated by tools/translations.py; edit the tables there. -->", "<resources>"]
        for name in english:
            if name in T and lang in T[name]:
                lines.append(f'    <string name="{name}">{esc(T[name][lang])}</string>')
        lines.append("</resources>")
        open(os.path.join(d, "strings.xml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
        pl = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for name, langs in PLURALS.items():
            pl.append(f'    <plurals name="{name}">')
            for q, text in langs[lang].items():
                pl.append(f'        <item quantity="{q}">{esc(text)}</item>')
            pl.append("    </plurals>")
        pl.append("</resources>")
        open(os.path.join(d, "plurals.xml"), "w", encoding="utf-8").write("\n".join(pl) + "\n")
        print(f"{folder}: {sum(1 for n in english if n in T and lang in T[n])} strings")


if __name__ == "__main__":
    main()
