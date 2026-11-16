async function enterApp() {
            document.getElementById('coverPage').classList.add('hidden');
            try {
                if (!document.fullscreenElement && document.documentElement.requestFullscreen) {
                    await document.documentElement.requestFullscreen();
                }
            } catch (e) {}
        }
        document.documentElement.style.overscrollBehaviorX = 'none';
        document.body.style.overscrollBehaviorX = 'none';

        let db = JSON.parse(localStorage.getItem('happyhome_db')) || {
            phones: [], apis: [], files: [],
            categories: {
                character: { authors: ['默认作者'], funcs: ['角色设定', '人物关系'] },
                worldbook: { authors: ['默认作者'], funcs: ['通用设定', '文风增强'] },
                theme: { authors: ['默认作者'], funcs: ['气泡美化', '状态栏'] },
                font: { authors: ['默认作者'], funcs: ['手写体', '可爱体'] }
            }
        };
        db.phones ||= []; db.apis ||= []; db.files ||= []; db.categories ||= {};
        db.categories.character ||= { authors: ['默认作者'], funcs: ['角色设定', '人物关系'] };
        db.categories.worldbook ||= { authors: ['默认作者'], funcs: ['通用设定', '文风增强'] };
        db.categories.theme ||= { authors: ['默认作者'], funcs: ['气泡美化', '状态栏'] };
        db.categories.font ||= { authors: ['默认作者'], funcs: ['手写体', '可爱体'] };
        db.themeAssets ||= [];

        let currentTab = 'phone';
        let selectedAuthor = '全部';
        let selectedFunc = '全部';
        let selectedPhoneType = 'free';
        let currentEditingFileId = null;
        let movingFileId = null;
        let movingAssetId = null;
        let currentThemeAssetId = null;
        let currentFontObjectURL = null;
        let currentFontFace = null;
        let themeEditorVisible = false;

        // 多选批量操作状态
        let isBatchMode = false;
        let selectedIds = new Set();

        function saveDB() { localStorage.setItem('happyhome_db', JSON.stringify(db)); }
        function escapeHTML(value) {
            return String(value ?? '').replace(/[&<>"']/g, ch => ({
                '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
            }[ch]));
        }

        function switchTab(tab, btnEl) {
            currentTab = tab;
            selectedAuthor = '全部';
            selectedFunc = '全部';
            selectedPhoneType = 'free';
            isBatchMode = false;
            selectedIds.clear();
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            btnEl.classList.add('active');
            renderMain();
        }

        function resolveIcon(item, fallbackEmoji) {
            if (item.icon && item.icon.trim() !== '') {
                return `<img src="${escapeHTML(item.icon.trim())}" onerror="this.parentElement.innerHTML='${fallbackEmoji}'">`;
            }
            try {
                const domain = new URL(item.url).hostname;
                return `<img src="https://api.iowen.cn/favicon/${domain}.png" onerror="this.src='https://favicon.im/${domain}?larger=true'; this.onerror=function(){this.parentElement.innerHTML='${fallbackEmoji}';}">`;
            } catch (e) { return fallbackEmoji; }
        }

        function el(tag, cls, html) {
            const e = document.createElement(tag);
            if (cls) e.className = cls;
            if (html !== undefined) e.innerHTML = html;
            return e;
        }
        function rerender() { const y = window.scrollY; renderMain(); window.scrollTo(0, y); }
        function exitBatch() { isBatchMode = false; selectedIds.clear(); }

        function countBy(section, key, val) {
            const isFile = section === 'worldbook' || section === 'character';
            const srcList = isFile ? db.files : db.themeAssets;
            return srcList.filter(x => x.section === section && (val === '全部' || x[key] === val)).length;
        }

        // 分类栏：用 addEventListener，彻底解决点不动的问题
        function renderCategoryBar(section) {
            const cats = db.categories[section] || { authors: [], funcs: [] };
            const bar = el('div', 'sub-category-bar');
            [['author', '按作者：', cats.authors || [], 'authors'], ['func', '按功能：', cats.funcs || [], 'funcs']].forEach(([type, label, arr, listKey]) => {
                const row = el('div', 'cat-row');
                row.appendChild(el('span', 'cat-label', label));
                const cur = type === 'author' ? selectedAuthor : selectedFunc;
                ['全部', ...arr].forEach(name => {
                    const chip = el('span', 'cat-chip' + (cur === name ? ' active' : ''));
                    chip.textContent = `${name} (${countBy(section, type, name)})`;
                    chip.addEventListener('click', () => setFilter(type, name));
                    row.appendChild(chip);
                });
                const add = el('button', 'cat-add-btn', '＋');
                add.addEventListener('click', () => addCategory(listKey));
                row.appendChild(add);
                bar.appendChild(row);
            });
            return bar;
        }

        function setFilter(type, val) {
            if (type === 'author') selectedAuthor = val; else selectedFunc = val;
            exitBatch(); renderMain();
        }
        function addCategory(type) {
            const val = prompt(`请输入${type === 'authors' ? '新作者名称' : '新功能分类名称'}：`);
            if (val && val.trim()) {
                const list = db.categories[currentTab][type];
                if (!list.includes(val.trim())) { list.push(val.trim()); saveDB(); renderMain(); }
            }
        }
        function setPhoneType(type) { selectedPhoneType = type; exitBatch(); renderMain(); }

        // 当前页面可见的项目（全选就选这些）
        function getVisible(kw) {
            const hit = s => (s || '').toLowerCase().includes(kw);
            if (currentTab === 'phone') return db.phones.filter(p => (p.type || 'free') === selectedPhoneType && (hit(p.name) || hit(p.url)));
            if (currentTab === 'api') return db.apis.filter(a => hit(a.name) || hit(a.url));
            const catOk = x => x.section === currentTab && (selectedAuthor === '全部' || x.author === selectedAuthor) && (selectedFunc === '全部' || x.func === selectedFunc);
            if (currentTab === 'worldbook' || currentTab === 'character') return db.files.filter(f => catOk(f) && (!kw || hit(f.name) || hit(f.content)));
            return db.themeAssets.filter(a => catOk(a) && (!kw || hit(a.name)));
        }

        function makeBatchBar(items) {
            const bar = el('div', 'batch-toolbar');
            const allSel = items.length > 0 && items.every(i => selectedIds.has(i.id));
            bar.appendChild(el('span', '', `已选 ${selectedIds.size} 项`));
            const box = el('div', 'batch-btns');
            const add = (cls, txt, fn) => { const b = el('button', cls, txt); b.addEventListener('click', fn); box.appendChild(b); };
            add('batch-btn-sel', allSel ? '取消全选' : '全选', () => {
                items.forEach(i => allSel ? selectedIds.delete(i.id) : selectedIds.add(i.id));
                rerender();
            });
            if (currentTab !== 'api') add('batch-btn-move', '移动', batchMove);
            add('batch-btn-del', '删除', batchDelete);
            add('batch-btn-sel', '完成', () => { exitBatch(); rerender(); });
            bar.appendChild(box);
            return bar;
        }

        function renderMain() {
            const container = document.getElementById('mainContent');
            const keyword = document.getElementById('searchInput').value.trim().toLowerCase();
            container.innerHTML = '';

            if (currentTab === 'backup') {
                container.innerHTML = `
                    <div class="backup-panel">
                        <h3>🗂️ HappyHome 数据备份</h3>
                        <p>将当前浏览器保存的小手机、API、世界书、人设、美化和字体资料打包导出。换设备后打开网址导入即可恢复。</p>
                        <button class="btn-primary" onclick="exportBackup()">导出完整备份文件</button>
                        <div class="form-group" style="margin-top:10px"><label>导入备份文件（JSON）</label><input type="file" id="backupInput" accept=".json,application/json"></div>
                        <button class="btn-primary" onclick="importBackup()">导入并恢复备份</button>
                    </div>`;
                return;
            }
            if (currentTab === 'phone') {
                container.innerHTML = `
                    <div class="phone-type-bar">
                        <button class="phone-type-btn ${selectedPhoneType === 'free' ? 'active' : ''}" onclick="setPhoneType('free')">📱 免费小手机</button>
                        <button class="phone-type-btn ${selectedPhoneType === 'paid' ? 'active' : ''}" onclick="setPhoneType('paid')">💎 付费小手机</button>
                    </div>`;
            } else if (currentTab !== 'api') {
                container.appendChild(renderCategoryBar(currentTab));
            }
            const items = getVisible(keyword);
            if (isBatchMode) container.appendChild(makeBatchBar(items));
            if (currentTab === 'phone' || currentTab === 'api') renderGrid(container, items);
            else renderList(container, items);
        }

        function renderGrid(container, items) {
            const isPhone = currentTab === 'phone';
            const grid = el('div', 'grid-container');
            if (!isBatchMode) {
                const add = el('div', 'square-card add-card', '<div class="plus">＋</div><span>添加</span>');
                add.addEventListener('click', () => openModal(isPhone ? 'phoneModal' : 'apiModal'));
                grid.appendChild(add);
            }
            items.forEach(it => {
                const card = el('div', 'square-card' + (selectedIds.has(it.id) ? ' selected' : ''));
                const badge = isPhone ? (it.pwd ? '<span class="badge-pwd">🔑</span>' : '') : (it.key ? '<span class="badge-pwd">🗝️</span>' : '');
                card.innerHTML = `${badge}<span class="sel-mark">✓</span><div class="square-icon">${resolveIcon(it, isPhone ? '📱' : '🔌')}</div><div class="square-title">${escapeHTML(it.name)}</div>`;
                bindLongPress(card, () => startBatch(it.id), () => {
                    if (isBatchMode) toggleSelectId(it.id); else openLaunchModal(isPhone ? 'phone' : 'api', it.id);
                });
                grid.appendChild(card);
            });
            container.appendChild(grid);
        }

        function renderList(container, items) {
            const sec = currentTab;
            const isFile = sec === 'worldbook' || sec === 'character';
            const list = el('div', 'list-container');
            if (!isBatchMode) {
                const label = sec === 'character' ? '手写 / 导入人设' : sec === 'worldbook' ? '手写 / 导入世界书' : sec === 'font' ? '导入字体文件' : '导入美化文件';
                const add = el('div', 'file-card add-card', `<span style="font-size:1.1rem;margin-right:6px;">＋</span><span>${label}</span>`);
              add.style.cssText = 'justify-content:center;padding:12px;cursor:pointer;';
                add.addEventListener('click', () => sec === 'character' ? openCharacterModal() : sec === 'worldbook' ? openImportModal() : openThemeImportModal(sec));
                list.appendChild(add);
                list.appendChild(el('div', 'hint-text', '💡 长按任意卡片：多选 / 全选 / 移动 / 删除'));
            }
            if (!items.length) list.appendChild(el('div', 'hint-text', '这个分类下还没有内容，点上面的＋导入吧～'));
            items.forEach(it => {
                const sel = selectedIds.has(it.id);
                const card = el('div', 'file-card' + (sel ? ' selected' : ''));
                const icon = isFile ? (sec === 'character' ? '👤' : '📄') : (it.kind === 'font' ? '🔤' : it.kind === 'css' ? '🎨' : '🌐');
                card.innerHTML = `
                    <div class="file-info">
                        ${isBatchMode ? `<span class="file-check">${sel ? '☑' : '☐'}</span>` : ''}
                        <div style="overflow:hidden">
                            <div class="file-name">${icon} ${escapeHTML(it.name)}</div>
                            <div class="file-tags"><span class="file-tag">✍️ ${escapeHTML(it.author)}</span><span class="file-tag">🏷️ ${escapeHTML(it.func)}</span></div>
                        </div>
                    </div>
                    ${isBatchMode ? '' : `<span style="color:var(--accent-caramel);font-size:0.8rem;">${isFile ? '编辑' : '预览'} ›</span>`}`;
                bindLongPress(card, () => startBatch(it.id), () => {
                    if (isBatchMode) toggleSelectId(it.id); else if (isFile) openEditFile(it.id); else openThemePreview(it.id);
                });
                list.appendChild(card);
            });
            container.appendChild(list);
        }

        // 长按：进入多选并选中当前项（已在多选里就切换选中）
        function startBatch(id) {
            if (isBatchMode) { toggleSelectId(id); return; }
            isBatchMode = true; selectedIds.clear(); selectedIds.add(id);
            if (navigator.vibrate) navigator.vibrate(20);
            rerender();
        }
        function toggleSelectId(id) {
            if (selectedIds.has(id)) selectedIds.delete(id); else selectedIds.add(id);
            rerender();
        }

        function batchDelete() {
            if (!selectedIds.size) return alert('请先选择要删除的项目');
            if (!confirm(`确定删除选中的 ${selectedIds.size} 项吗？`)) return;
            if (currentTab === 'phone') db.phones = db.phones.filter(x => !selectedIds.has(x.id));
            else if (currentTab === 'api') db.apis = db.apis.filter(x => !selectedIds.has(x.id));
            else if (currentTab === 'worldbook' || currentTab === 'character') db.files = db.files.filter(x => !selectedIds.has(x.id));
            else {
                const ids = [...selectedIds];
                db.themeAssets = db.themeAssets.filter(x => !selectedIds.has(x.id));
                ids.forEach(id => themeDBAction('delete', id).catch(() => {}));
            }
            saveDB(); exitBatch(); renderMain();
        }

        function batchMove() {
            if (!selectedIds.size) return alert('请先选择要移动的项目');
            if (currentTab === 'phone') {
                const to = selectedPhoneType === 'free' ? 'paid' : 'free';
                if (!confirm(`把选中的 ${selectedIds.size} 个小手机移到「${to === 'paid' ? '付费' : '免费'}小手机」吗？`)) return;
                db.phones.forEach(p => { if (selectedIds.has(p.id)) p.type = to; });
                saveDB(); exitBatch(); renderMain(); return;
            }
            openBatchMoveModal();
        }

        function openBatchMoveModal() {
            const cats = db.categories[currentTab] || { authors: ['默认作者'], funcs: [] };
            document.getElementById('batchMoveAuthorSel').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}">${escapeHTML(a)}</option>`).join('');
            document.getElementById('batchMoveFuncSel').innerHTML = (cats.funcs || []).map(fn => `<option value="${escapeHTML(fn)}">${escapeHTML(fn)}</option>`).join('');
            openModal('batchMoveModal');
        }

        function confirmBatchMove() {
            const author = document.getElementById('batchMoveAuthorSel').value;
            const func = document.getElementById('batchMoveFuncSel').value;
            const target = (currentTab === 'worldbook' || currentTab === 'character') ? db.files : db.themeAssets;
            target.forEach(x => { if (selectedIds.has(x.id)) { x.author = author; x.func = func; } });
            saveDB(); exitBatch(); closeModal('batchMoveModal'); renderMain();
        }

        // 长按：用 pointer 事件，手机和电脑都稳定
        let swallowClick = false;
        function bindLongPress(node, longAction, clickAction) {
            let timer = null, sx = 0, sy = 0;
            const cancel = () => { clearTimeout(timer); timer = null; };
            node.addEventListener('pointerdown', e => {
                swallowClick = false; sx = e.clientX; sy = e.clientY; cancel();
                timer = setTimeout(() => { timer = null; swallowClick = true; longAction(); }, 500);
            });
            node.addEventListener('pointermove', e => {
                if (timer && (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10)) cancel();
            });
            ['pointerup', 'pointercancel', 'pointerleave'].forEach(t => node.addEventListener(t, cancel));
            node.addEventListener('contextmenu', e => e.preventDefault());
            node.addEventListener('click', () => { if (swallowClick) { swallowClick = false; return; } clickAction(); });
        }

        let phoneIconData = '';
        function openPhoneIconOptions() { document.getElementById('pIconOptions').classList.toggle('hidden'); }
        function setPhoneIconByUrl() {
            const url = prompt('请输入图片 URL：', document.getElementById('pIcon').value || '');
            if (url === null) return;
            document.getElementById('pIcon').value = url.trim();
            document.getElementById('pIconOptions').classList.add('hidden');
            previewPhoneIconUrl();
        }
        function handlePhoneIconFile(input) {
            const file = input.files && input.files[0];
            if (!file || !file.type.startsWith('image/')) return;
            const reader = new FileReader();
            reader.onload = event => {
                phoneIconData = event.target.result;
                document.getElementById('pIcon').value = '';
                document.getElementById('pIconPreview').innerHTML = `<img src="${phoneIconData}" alt="图标预览">`;
            };
            reader.readAsDataURL(file);
        }
        function previewPhoneIconUrl() {
            const url = document.getElementById('pIcon').value.trim();
            phoneIconData = '';
            document.getElementById('pIconPreview').innerHTML = url ? `<img src="${escapeHTML(url)}" alt="预览" onerror="this.parentElement.textContent='＋'">` : '＋';
        }

        function savePhone() {
            const name = document.getElementById('pName').value.trim();
            let url = document.getElementById('pUrl').value.trim();
            const icon = phoneIconData || document.getElementById('pIcon').value.trim();
            const entry = document.getElementById('pEntry').value;
            const pwd = document.getElementById('pPwd').value.trim();
            const type = document.getElementById('pType').value || 'free';
            if (!name || !url) return alert('请填写名称和链接');
            if (!url.startsWith('http')) url = 'https://' + url;
            db.phones.push({ id: Date.now(), name, url, icon, entry, pwd, type });
            saveDB(); closeModal('phoneModal');
            ['pName', 'pUrl', 'pIcon', 'pPwd'].forEach(id => document.getElementById(id).value = '');
            document.getElementById('pEntry').value = '';
            document.getElementById('pIconPreview').textContent = '＋';
            phoneIconData = ''; renderMain();
        }

        function copyField(id) { const value = document.getElementById(id).value; if (value) copyText(value); }

        function saveApi() {
            const name = document.getElementById('aName').value.trim();
            let url = document.getElementById('aUrl').value.trim();
            const email = document.getElementById('aEmail').value.trim();
            const pwd = document.getElementById('aPwd').value.trim();
            const baseUrl = document.getElementById('aBaseUrl').value.trim();
            const key = document.getElementById('aKey').value.trim();
            if (!name || !url) return alert('请填写名称和链接');
            if (!url.startsWith('http')) url = 'https://' + url;
            db.apis.push({ id: Date.now(), name, url, email, pwd, baseUrl, key });
            saveDB(); closeModal('apiModal');
            ['aName', 'aUrl', 'aEmail', 'aPwd', 'aBaseUrl', 'aKey'].forEach(id => document.getElementById(id).value = '');
            renderMain();
        }

        function buildEternalIntent(rawUrl) {
            try {
                const target = new URL(rawUrl);
                if (target.protocol !== 'http:' && target.protocol !== 'https:') return rawUrl;
                const fallback = encodeURIComponent(target.href);
                return `intent://${target.host}${target.pathname}${target.search}#Intent;scheme=${target.protocol.slice(0, -1)};package=com.sevensix.everlasting;S.browser_fallback_url=${fallback};end`;
            } catch (e) { return rawUrl; }
        }

        function openLaunchModal(type, id) {
            const item = type === 'phone' ? db.phones.find(x => x.id === id) : db.apis.find(x => x.id === id);
            if (!item) return;
            document.getElementById('launchTitle').innerText = item.name;
            let detailsHtml = `<div>🔗 链接：${escapeHTML(item.url)}</div>`;
            if (type === 'api') {
                if (item.email) detailsHtml += `<div>📧 邮箱：${escapeHTML(item.email)}</div>`;
                if (item.pwd) detailsHtml += `<div>🔑 密码：${escapeHTML(item.pwd)}</div>`;
                if (item.baseUrl) detailsHtml += `<div>🌐 Base URL：${escapeHTML(item.baseUrl)}</div>`;
                if (item.key) detailsHtml += `<div>🗝️ Key：${escapeHTML(item.key)}</div>`;
            } else {
                if (item.entry) detailsHtml += `<div>进入方式：${escapeHTML(item.entry)}</div>`;
                if (item.pwd) detailsHtml += `<div>🔑 密码：${escapeHTML(item.pwd)}</div>`;
            }
            document.getElementById('launchDetails').innerHTML = detailsHtml;
            const cleanUrl = item.url.replace(/^https?:\/\//, '');
            const encUrl = encodeURIComponent(item.url);
            document.getElementById('browserButtons').innerHTML = `
                <a href="${escapeHTML(item.url)}" target="_blank" rel="noopener" class="browser-btn">🧭 默认浏览器</a>
                <a href="${escapeHTML(buildEternalIntent(item.url))}" class="browser-btn">♾️ 永恒浏览器</a>
                <a href="quark://${escapeHTML(cleanUrl)}" class="browser-btn">🐿️ 夸克</a>
                <a href="mttbrowser://url=${encUrl}" class="browser-btn">🐧 QQ</a>
                <a href="via://${escapeHTML(item.url)}" class="browser-btn">🔗 Via</a>
                <a href="googlechrome://navigate?url=${encUrl}" class="browser-btn">🌐 Chrome</a>
                <a href="microsoft-edge-https://${escapeHTML(cleanUrl)}" class="browser-btn">🌊 Edge</a>
                <button class="browser-btn" style="grid-column:span 2;background:var(--card-cream);" data-u="${escapeHTML(item.url)}" onclick="copyText(this.dataset.u)">📋 复制链接</button>`;
            document.getElementById('deleteItemBtn').onclick = () => {
                if (confirm('确定要删除吗？')) {
                    if (type === 'phone') db.phones = db.phones.filter(x => x.id !== id);
                    else db.apis = db.apis.filter(x => x.id !== id);
                    saveDB(); closeModal('launchModal'); renderMain();
                }
            };
            openModal('launchModal');
        }

        function copyText(txt) {
            if (navigator.clipboard) { navigator.clipboard.writeText(txt).then(() => alert('已复制！')).catch(() => alert('复制失败')); }
        }

        function openImportModal() {
            const cats = db.categories.worldbook;
            document.getElementById('impAuthor').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}">${escapeHTML(a)}</option>`).join('');
            document.getElementById('impFunc').innerHTML = (cats.funcs || []).map(f => `<option value="${escapeHTML(f)}">${escapeHTML(f)}</option>`).join('');
            document.getElementById('fileInput').value = '';
            document.getElementById('manualWorldName').value = '';
            document.getElementById('manualWorldContent').value = '';
            openModal('importFileModal');
        }

        async function parseDocxArrayBuffer(arrayBuffer) {
            try { const result = await mammoth.extractRawText({ arrayBuffer }); return result.value || '(空文档)'; } catch (e) { return 'DOCX 解析失败'; }
        }
        async function readTextFile(file) {
            return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = e => resolve(e.target.result);
                reader.onerror = () => reject(new Error('读取失败'));
                reader.readAsText(file, 'UTF-8');
            });
        }
        async function importContentFiles(fileList, section, author, func) {
            for (const file of fileList) {
                const lowerName = file.name.toLowerCase();
                if (lowerName.endsWith('.zip')) {
                    try {
                        const zip = await JSZip.loadAsync(file);
                        for (const [relativePath, zipEntry] of Object.entries(zip.files)) {
                            if (zipEntry.dir || relativePath.startsWith('__MACOSX')) continue;
                            const cleanName = relativePath.split('/').pop();
                            if (!cleanName) continue;
                            let content = '';
                            if (cleanName.toLowerCase().endsWith('.docx')) content = await parseDocxArrayBuffer(await zipEntry.async('arraybuffer'));
                            else content = await zipEntry.async('string');
                            db.files.push({ id: Date.now() + Math.random(), section, name: cleanName, author, func, content });
                        }
                    } catch (e) { alert('ZIP 解压失败'); }
                } else {
                    const content = lowerName.endsWith('.docx') ? await parseDocxArrayBuffer(await file.arrayBuffer()) : await readTextFile(file);
                    db.files.push({ id: Date.now() + Math.random(), section, name: file.name, author, func, content });
                }
            }
        }

        async function handleFileImport() {
            const files = document.getElementById('fileInput').files;
            const author = document.getElementById('impAuthor').value;
            const func = document.getElementById('impFunc').value;
            const manualName = document.getElementById('manualWorldName').value.trim();
            const manualContent = document.getElementById('manualWorldContent').value;
            let added = false;
            if (manualContent.trim()) { db.files.push({ id: Date.now() + Math.random(), section: 'worldbook', name: manualName || '手写世界书.txt', author, func, content: manualContent }); added = true; }
            if (files.length) { await importContentFiles(files, 'worldbook', author, func); added = true; }
            if (!added) return alert('请填写手写内容或选择文件。');
            saveDB(); closeModal('importFileModal'); renderMain(); alert('导入完成！');
        }

        function openCharacterModal() {
            const cats = db.categories.character;
            document.getElementById('charAuthor').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}">${escapeHTML(a)}</option>`).join('');
            document.getElementById('charFunc').innerHTML = (cats.funcs || []).map(f => `<option value="${escapeHTML(f)}">${escapeHTML(f)}</option>`).join('');
            document.getElementById('charManualName').value = ''; document.getElementById('charManualContent').value = ''; document.getElementById('charFileInput').value = '';
            openModal('characterModal');
        }

        async function handleCharacterImport() {
            const files = document.getElementById('charFileInput').files;
            const author = document.getElementById('charAuthor').value;
            const func = document.getElementById('charFunc').value;
            const manualName = document.getElementById('charManualName').value.trim();
            const manualContent = document.getElementById('charManualContent').value;
            let added = false;
            if (manualContent.trim()) { db.files.push({ id: Date.now() + Math.random(), section: 'character', name: manualName || '手写人设.txt', author, func, content: manualContent }); added = true; }
            if (files.length) { await importContentFiles(files, 'character', author, func); added = true; }
            if (!added) return alert('请填写或选择文件。');
            saveDB(); closeModal('characterModal'); renderMain(); alert('人设导入完成！');
        }

        function openMoveCategoryModal(id) {
            const f = db.files.find(x => x.id === id);
            if (!f) return;
            movingFileId = id; movingAssetId = null;
            const cats = db.categories[f.section] || db.categories.worldbook;
            document.getElementById('moveFileName').innerText = `📄 ${f.name}`;
            document.getElementById('moveAuthorSel').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}" ${a === f.author ? 'selected' : ''}>${escapeHTML(a)}</option>`).join('');
            document.getElementById('moveFuncSel').innerHTML = (cats.funcs || []).map(fn => `<option value="${escapeHTML(fn)}" ${fn === f.func ? 'selected' : ''}>${escapeHTML(fn)}</option>`).join('');
            openModal('moveCatModal');
        }

        function openMoveAssetCategoryModal(id) {
            const asset = db.themeAssets.find(x => x.id === id);
            if (!asset) return;
            movingFileId = null; movingAssetId = id;
            const cats = db.categories[asset.section];
            document.getElementById('moveFileName').innerText = `📄 ${asset.name}`;
            document.getElementById('moveAuthorSel').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}" ${a === asset.author ? 'selected' : ''}>${escapeHTML(a)}</option>`).join('');
            document.getElementById('moveFuncSel').innerHTML = (cats.funcs || []).map(fn => `<option value="${escapeHTML(fn)}" ${fn === asset.func ? 'selected' : ''}>${escapeHTML(fn)}</option>`).join('');
            openModal('moveCatModal');
        }

        function confirmMoveCategory() {
            const author = document.getElementById('moveAuthorSel').value;
            const func = document.getElementById('moveFuncSel').value;
            if (movingAssetId !== null) {
                const asset = db.themeAssets.find(x => x.id === movingAssetId);
                if (asset) { asset.author = author; asset.func = func; }
            } else {
                const f = db.files.find(x => x.id === movingFileId);
                if (f) { f.author = author; f.func = func; }
            }
            saveDB(); movingFileId = null; movingAssetId = null; closeModal('moveCatModal'); renderMain();
        }

        function openEditFile(id) {
            const f = db.files.find(x => x.id === id);
            if (!f) return;
            currentEditingFileId = id;
            document.getElementById('editFileName').value = f.name;
            document.getElementById('editFileContent').value = f.content;
            openModal('editFileModal');
        }

        function saveFileEdit() {
            const f = db.files.find(x => x.id === currentEditingFileId);
            if (f) { f.name = document.getElementById('editFileName').value.trim() || f.name; f.content = document.getElementById('editFileContent').value; saveDB(); closeModal('editFileModal'); renderMain(); }
        }

        async function copyCurrentFile() {
            try { await navigator.clipboard.writeText(document.getElementById('editFileContent').value); alert('已复制'); } catch(e){}
        }

        function downloadCurrentFile() {
            const f = db.files.find(x => x.id === currentEditingFileId);
            if (!f) return;
            downloadTextFile(f.name.replace(/\.docx$/i, '.txt'), document.getElementById('editFileContent').value);
        }

        function downloadTextFile(name, content) {
            const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        }

        function deleteCurrentFile() {
            if (!confirm('确定删除吗？')) return;
            db.files = db.files.filter(x => x.id !== currentEditingFileId);
            saveDB(); closeModal('editFileModal'); renderMain();
        }

        function blobToDataURL(blob) {
            return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = () => resolve(reader.result);
                reader.onerror = () => reject(reader.error);
                reader.readAsDataURL(blob);
            });
        }
        function dataURLToBlob(dataURL) {
            const parts = dataURL.split(',');
            const mime = (parts[0].match(/:(.*?);/) || [])[1] || 'application/octet-stream';
            const binary = atob(parts[1] || '');
          const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            return new Blob([bytes], { type: mime });
        }

        async function exportBackup() {
            try {
                const backup = { format: 'HappyHomeBackup', version: 1, db, assets: [] };
                for (const asset of (db.themeAssets || [])) {
                    const stored = await themeDBAction('get', asset.id);
                    if (stored && stored.blob) {
                        backup.assets.push({ id: asset.id, name: stored.name || asset.name, type: stored.type || stored.blob.type, data: await blobToDataURL(stored.blob) });
                    }
                }
                const blob = new Blob([JSON.stringify(backup)], { type: 'application/json;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a'); a.href = url; a.download = `HappyHome-备份.json`; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
                alert('导出成功');
            } catch(e) { alert('导出失败'); }
        }

        async function importBackup() {
            const input = document.getElementById('backupInput');
            const file = input && input.files && input.files[0];
            if (!file) return alert('请选择备份文件');
            if (!confirm('导入将覆盖现有数据，确定继续吗？')) return;
            try {
                const backup = JSON.parse(await file.text());
                db = backup.db; saveDB();
                for (const item of (backup.assets || [])) {
                    await themeDBAction('put', item.id, { id: item.id, name: item.name, type: item.type, blob: dataURLToBlob(item.data) });
                }
                alert('恢复成功，即将刷新页面'); location.reload();
            } catch(e) { alert('恢复失败'); }
        }

        const themeDBPromise = new Promise((resolve, reject) => {
            if (!('indexedDB' in window)) return reject(new Error('不支持IndexedDB'));
            const request = indexedDB.open('HappyHomeThemeFiles', 1);
            request.onupgradeneeded = e => { const db = e.target.result; if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' }); };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });

        async function themeDBAction(mode, id, value) {
            const database = await themeDBPromise;
            return new Promise((resolve, reject) => {
                const transaction = database.transaction('assets', 'readwrite');
                const store = transaction.objectStore('assets');
                let request;
                if (mode === 'put') request = store.put(value);
                if (mode === 'get') request = store.get(id);
                if (mode === 'delete') request = store.delete(id);
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error);
            });
        }

        function getAssetKind(name) {
            const ext = name.split('.').pop().toLowerCase();
            if (['ttf', 'otf', 'woff', 'woff2'].includes(ext)) return 'font';
            if (ext === 'css') return 'css';
            if (['html', 'htm'].includes(ext)) return 'html';
            if (ext === 'json') return 'json';
            return null;
        }

        function openThemeImportModal(section) {
            const cats = db.categories[section] || { authors: [], funcs: [] };
            document.getElementById('themeImportTitle').innerText = section === 'font' ? '🔤 导入字体' : '🎀 导入美化';
            document.getElementById('themeFileInput').value = '';
            document.getElementById('themeImpAuthor').innerHTML = (cats.authors || []).map(a => `<option value="${escapeHTML(a)}">${escapeHTML(a)}</option>`).join('');
            document.getElementById('themeImpFunc').innerHTML = (cats.funcs || []).map(f => `<option value="${escapeHTML(f)}">${escapeHTML(f)}</option>`).join('');
            openModal('themeImportModal');
        }

        async function handleThemeImport() {
            const files = document.getElementById('themeFileInput').files;
            if (!files.length) return alert('请选择文件');
            const author = document.getElementById('themeImpAuthor').value;
            const func = document.getElementById('themeImpFunc').value;
            for (const file of files) {
                const kind = getAssetKind(file.name);
                if (!kind) continue;
                const section = kind === 'font' ? 'font' : 'theme';
                const id = `${Date.now()}_${Math.random().toString(36).slice(2)}`;
                let content = kind !== 'font' ? await readTextFile(file) : '';
                await themeDBAction('put', id, { id, blob: file, name: file.name, type: file.type });
                db.themeAssets.push({ id, name: file.name, section, author, func, kind, content });
            }
            saveDB(); closeModal('themeImportModal'); renderMain(); alert('导入完成');
        }

        async function openThemePreview(id) {
            const asset = db.themeAssets.find(x => x.id === id);
            if (!asset) return;
            currentThemeAssetId = id; themeEditorVisible = false;
            document.getElementById('themeEditName').value = asset.name;
            document.getElementById('themeEditContent').value = asset.content || '';
            document.getElementById('themeTextEditor').classList.add('hidden');
            document.getElementById('themeEditToggle').classList.toggle('hidden', asset.kind === 'font');
            document.getElementById('themePreviewTitle').innerText = asset.name;
            document.getElementById('themePreviewArea').innerHTML = '';
            document.getElementById('fontSampleArea').classList.add('hidden');
            document.getElementById('themePreviewHint').innerText = '';
            if (currentFontFace) { try { document.fonts.delete(currentFontFace); } catch(e){} currentFontFace = null; }
            if (currentFontObjectURL) { URL.revokeObjectURL(currentFontObjectURL); currentFontObjectURL = null; }

            if (asset.kind === 'font') {
                const stored = await themeDBAction('get', id);
                currentFontObjectURL = URL.createObjectURL(stored.blob);
                const fontFamily = `Font_${id}`;
                currentFontFace = new FontFace(fontFamily, `url("${currentFontObjectURL}")`);
                await currentFontFace.load(); document.fonts.add(currentFontFace);
                document.getElementById('fontSampleArea').classList.remove('hidden');
                document.getElementById('fontPreview').style.fontFamily = `"${fontFamily}"`;
            } else if (asset.kind === 'css') {
                const frame = document.createElement('iframe');
                frame.srcdoc = `<style>${asset.content}</style><h3>预览</h3>`;
                document.getElementById('themePreviewArea').appendChild(frame);
            } else if (asset.kind === 'html') {
                const frame = document.createElement('iframe'); frame.srcdoc = asset.content;
                document.getElementById('themePreviewArea').appendChild(frame);
            }
            openModal('themePreviewModal');
        }

        function toggleThemeEditor() {
            themeEditorVisible = !themeEditorVisible;
            document.getElementById('themeTextEditor').classList.toggle('hidden', !themeEditorVisible);
        }

        async function saveThemeAsset() {
            const asset = db.themeAssets.find(x => x.id === currentThemeAssetId);
            if (!asset) return;
            asset.name = document.getElementById('themeEditName').value.trim() || asset.name;
            if (asset.kind !== 'font' && themeEditorVisible) {
                asset.content = document.getElementById('themeEditContent').value;
                const stored = await themeDBAction('get', asset.id);
                if (stored) await themeDBAction('put', asset.id, { ...stored, blob: new Blob([asset.content]), name: asset.name });
            }
            saveDB(); renderMain(); alert('保存成功');
        }

        async function downloadThemeAsset() {
            const stored = await themeDBAction('get', currentThemeAssetId);
            if (!stored) return;
            const url = URL.createObjectURL(stored.blob);
            const a = document.createElement('a'); a.href = url; a.download = stored.name; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        }

        async function deleteThemeAsset() {
            if (!confirm('确定删除吗？')) return;
            await themeDBAction('delete', currentThemeAssetId);
            db.themeAssets = db.themeAssets.filter(x => x.id !== currentThemeAssetId);
            saveDB(); closeModal('themePreviewModal'); renderMain();
        }

        function openModal(id) { document.getElementById(id).classList.add('show'); }
        function closeModal(id) { document.getElementById(id).classList.remove('show'); }

        document.querySelectorAll('.modal-overlay').forEach(overlay => {
            overlay.addEventListener('click', e => { if (e.target === overlay) overlay.classList.remove('show'); });
        });

        /* ===== 补丁：分类长按/API分类/字体预览文字 ===== */
        db.apiCats ||= ['默认分类'];
        db.apis.forEach(a => { if (!a.cat) a.cat = db.apiCats[0]; });
        let selectedApiCat = '全部';
        document.head.appendChild(Object.assign(document.createElement('style'), { textContent: '#fontSampleText{min-height:64px!important}' }));

        function catArr(section, type) {
            if (section === 'api') return db.apiCats;
            return db.categories[section][type === 'author' ? 'authors' : 'funcs'];
        }
        function countBy(section, key, val) {
            const isFile = section === 'worldbook' || section === 'character';
            const src = section === 'api' ? db.apis : isFile ? db.files : db.themeAssets;
            return src.filter(x => (section === 'api' || x.section === section) && (val === '全部' || x[key] === val)).length;
        }
        function addCat(section, type) {
            const v = prompt('请输入新分类名称：');
            if (!v || !v.trim()) return;
            const arr = catArr(section, type);
            if (arr.includes(v.trim())) return alert('已有同名分类');
            arr.push(v.trim()); saveDB(); renderMain();
        }
        // 长按分类：重命名 / 删除
        function manageCat(section, type, name) {
            if (name === '全部') return;
            const arr = catArr(section, type);
            const v = prompt(`分类「${name}」\n· 输入新名称 = 重命名\n· 输入 删除 = 删除此分类`, name);
            if (v === null) return;
            const nv = v.trim();
            if (!nv || nv === name) return;
            const isFile = section === 'worldbook' || section === 'character';const items = section === 'api' ? db.apis : isFile ? db.files : db.themeAssets;
            const key = section === 'api' ? 'cat' : type;
            const mine = x => (section === 'api' || x.section === section) && x[key] === name;
            if (nv === '删除') {
                if (arr.length <= 1) return alert('至少要保留一个分类');
                arr.splice(arr.indexOf(name), 1);
                items.forEach(x => { if (mine(x)) x[key] = arr[0]; });
            } else {
                if (arr.includes(nv)) return alert('已有同名分类');
                arr[arr.indexOf(name)] = nv;
                items.forEach(x => { if (mine(x)) x[key] = nv; });
            }
            if (type === 'author') selectedAuthor = '全部'; else if (type === 'func') selectedFunc = '全部'; else selectedApiCat = '全部';
            saveDB(); renderMain();
        }
        function renderCategoryBar(section) {
            const bar = el('div', 'sub-category-bar');
            const rows = section === 'api' ? [['cat', '分类：']] : [['author', '按作者：'], ['func', '按功能：']];
            rows.forEach(([type, label]) => {
                const row = el('div', 'cat-row');
                row.appendChild(el('span', 'cat-label', label));
                const cur = type === 'author' ? selectedAuthor : type === 'func' ? selectedFunc : selectedApiCat;
                ['全部', ...catArr(section, type)].forEach(name => {
                    const chip = el('span', 'cat-chip' + (cur === name ? ' active' : ''));
                    chip.textContent = `${name} (${countBy(section, type, name)})`;
                    bindLongPress(chip, () => manageCat(section, type, name), () => setFilter(type, name));
                    row.appendChild(chip);
                });
                const add = el('button', 'cat-add-btn', '＋');
                add.addEventListener('click', () => addCat(section, type));
                row.appendChild(add);
                bar.appendChild(row);
            });
            bar.appendChild(el('div', 'hint-text', '💡 长按分类名：重命名 / 删除'));
            return bar;
        }
        function setFilter(type, val) {
            if (type === 'author') selectedAuthor = val; else if (type === 'func') selectedFunc = val; else selectedApiCat = val;
            exitBatch(); renderMain();
        }
        function getVisible(kw) {
            const hit = s => (s || '').toLowerCase().includes(kw);
            if (currentTab === 'phone') return db.phones.filter(p => (p.type || 'free') === selectedPhoneType && (hit(p.name) || hit(p.url)));
            if (currentTab === 'api') return db.apis.filter(a => (selectedApiCat === '全部' || a.cat === selectedApiCat) && (hit(a.name) || hit(a.url)));
            const catOk = x => x.section === currentTab && (selectedAuthor === '全部' || x.author === selectedAuthor) && (selectedFunc === '全部' || x.func === selectedFunc);
            if (currentTab === 'worldbook' || currentTab === 'character') return db.files.filter(f => catOk(f) && (!kw || hit(f.name) || hit(f.content)));
            return db.themeAssets.filter(a => catOk(a) && (!kw || hit(a.name)));
        }
        // API 页显示分类栏
        const _rm = renderMain;
        renderMain = function () {
            _rm();
            if (currentTab === 'api') { const c = document.getElementById('mainContent'); c.insertBefore(renderCategoryBar('api'), c.firstChild); }
        };
        // API 的批量移动 = 换分类
        function makeBatchBar(items) {
            const bar = el('div', 'batch-toolbar');
            const allSel = items.length > 0 && items.every(i => selectedIds.has(i.id));
            bar.appendChild(el('span', '', `已选 ${selectedIds.size} 项`));
            const box = el('div', 'batch-btns');
            const add = (cls, txt, fn) => { const b = el('button', cls, txt); b.addEventListener('click', fn); box.appendChild(b); };
            add('batch-btn-sel', allSel ? '取消全选' : '全选', () => { items.forEach(i => allSel ? selectedIds.delete(i.id) : selectedIds.add(i.id)); rerender(); });
            add('batch-btn-move', '移动', batchMove);
            add('batch-btn-del', '删除', batchDelete);
            add('batch-btn-sel', '完成', () => { exitBatch(); rerender(); });
            bar.appendChild(box);
            return bar;
        }
        function openBatchMoveModal() {
            const isApi = currentTab === 'api';
            const aSel = document.getElementById('batchMoveAuthorSel'), fSel = document.getElementById('batchMoveFuncSel');
            const opt = a => `<option value="${escapeHTML(a)}">${escapeHTML(a)}</option>`;
            aSel.innerHTML = (isApi ? db.apiCats : db.categories[currentTab].authors).map(opt).join('');
            fSel.innerHTML = isApi ? '' : db.categories[currentTab].funcs.map(opt).join('');
            fSel.parentElement.style.display = isApi ? 'none' : '';
            aSel.previousElementSibling.textContent = isApi ? '目标分类' : '目标作者分类';
            openModal('batchMoveModal');
        }
        function confirmBatchMove() {
            const author = document.getElementById('batchMoveAuthorSel').value;
            const func = document.getElementById('batchMoveFuncSel').value;
            if (currentTab === 'api') db.apis.forEach(x => { if (selectedIds.has(x.id)) x.cat = author; });
            else (currentTab === 'worldbook' || currentTab === 'character' ? db.files : db.themeAssets).forEach(x => { if (selectedIds.has(x.id)) { x.author = author; x.func = func; } });
            saveDB(); exitBatch(); closeModal('batchMoveModal'); renderMain();
        }
        // 添加 API 时选分类
        document.getElementById('aName').closest('.form-group').insertAdjacentHTML('beforebegin', '<div class="form-group"><label>分类</label><select id="aCat"></select></div>');
        const _om = openModal;
        openModal = function (id) {
            if (id === 'apiModal') document.getElementById('aCat').innerHTML = db.apiCats.map(c => `<option value="${escapeHTML(c)}" ${c === selectedApiCat ? 'selected' : ''}>${escapeHTML(c)}</option>`).join('');
            _om(id);
        };
        const _sa = saveApi;
        saveApi = function () {
            const c = document.getElementById('aCat').value, n = db.apis.length;
            _sa();
            if (db.apis.length > n) { db.apis[db.apis.length - 1].cat = c; saveDB(); renderMain(); }
        };
        // 字体预览文字：想打什么就显示什么
        const _fs = document.getElementById('fontSampleText'), _fp = document.getElementById('fontPreview');
        _fp.textContent = _fs.value;
        _fs.addEventListener('input', () => { _fp.textContent = _fs.value || ' '; });

        /* ===== 补丁：侧滑返回 / 手机返回键 ===== */
        const tabStack = [];
        const _stOrig = switchTab;
        switchTab = function (tab, btn) { if (tab !== currentTab) tabStack.push(currentTab); _stOrig(tab, btn); };

        function toast(msg) {
            let t = document.getElementById('hhToast');
            if (!t) {
                t = document.createElement('div'); t.id = 'hhToast';
                t.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:rgba(92,75,59,.9);color:#fff;padding:8px 16px;border-radius:16px;font-size:.8rem;z-index:300;transition:opacity .3s;pointer-events:none';
                document.body.appendChild(t);
            }
            t.textContent = msg; t.style.opacity = 1;
            clearTimeout(t._t); t._t = setTimeout(() => { t.style.opacity = 0; }, 1800);
        }

        // 返回一步：返回 true 表示处理了
        function handleBack() {
            if (!document.getElementById('coverPage').classList.contains('hidden')) return true;
            const open = [...document.querySelectorAll('.modal-overlay.show')];
            if (open.length) { closeModal(open[open.length - 1].id); return true; }
            if (isBatchMode) { exitBatch(); rerender(); return true; }
            const kw = document.getElementById('searchInput');
            if (selectedAuthor !== '全部' || selectedFunc !== '全部' || selectedApiCat !== '全部' || selectedPhoneType !== 'free' || kw.value) {
                selectedAuthor = selectedFunc = selectedApiCat = '全部'; selectedPhoneType = 'free'; kw.value = '';
                renderMain(); return true;
            }
            if (tabStack.length) {
                const prev = tabStack.pop();
                const btn = [...document.querySelectorAll('.tab-btn')].find(b => (b.getAttribute('onclick') || '').includes(`'${prev}'`));
                _stOrig(prev, btn); return true;
            }
            return false;
        }

        // 手机返回键 / 浏览器返回：先在页面内返回，不直接退出
        let ignorePop = 0, lastRootBack = 0;
        history.replaceState({ hh: 0 }, '');
        history.pushState({ hh: 1 }, '');
        window.addEventListener('popstate', () => {
            if (Date.now() < ignorePop) { history.pushState({ hh: 1 }, ''); return; }
            if (handleBack()) { history.pushState({ hh: 1 }, ''); return; }
            if (Date.now() - lastRootBack < 2000) { history.back(); return; }
            lastRootBack = Date.now(); toast('再返回一次退出'); history.pushState({ hh: 1 }, '');
        });

        // 屏幕左右边缘向内滑动 = 返回
        let _sx = 0, _sy = 0, _edge = 0;
        document.addEventListener('touchstart', e => {
            const t = e.touches[0]; _sx = t.clientX; _sy = t.clientY;
            _edge = _sx <= 28 ? 1 : _sx >= window.innerWidth - 28 ? -1 : 0;
        }, { passive: true });
        document.addEventListener('touchend', e => {
            if (!_edge) return;
            const t = e.changedTouches[0];
            const dx = (t.clientX - _sx) * _edge, dy = Math.abs(t.clientY - _sy);
            _edge = 0;
            if (dx > 70 && dy < 60) { ignorePop = Date.now() + 700; if (!handleBack()) toast('已经在首页啦～'); }
        }, { passive: true });

        /* ===== 补丁：字体页去掉空白框 + 支持粘贴字体 URL ===== */
        document.getElementById('themeFileInput').closest('.form-group').insertAdjacentHTML('afterend', '<div class="form-group" id="themeFontUrlBox"><label>或粘贴字体 URL（字体页可用）</label><input type="url" id="themeFontUrl" placeholder="https://.../xxx.ttf"></div>');
        const _otim = openThemeImportModal;
        openThemeImportModal = function (section) {
            _otim(section);
            document.getElementById('themeFontUrl').value = '';
            document.getElementById('themeFontUrlBox').style.display = section === 'font' ? '' : 'none';
        };

        async function importFontUrl(url, author, func) {
            const name = decodeURIComponent(url.split('?')[0].split('/').pop() || '网络字体');
            const id = `${Date.now()}_${Math.random().toString(36).slice(2)}`;
            let blob = null;
            try { const r = await fetch(url); if (!r.ok) throw 0; blob = await r.blob(); } catch (e) {}
            if (blob) await themeDBAction('put', id, { id, blob, name, type: blob.type });
            db.themeAssets.push({ id, name, section: 'font', author, func, kind: 'font', content: '', url: blob ? '' : url });
        }
async function handleThemeImport() {
            const files = document.getElementById('themeFileInput').files;
            const url = document.getElementById('themeFontUrl').value.trim();
            if (!files.length && !url) return alert('请选择文件或粘贴字体 URL');
            if (url && !/^https?:\/\//i.test(url)) return alert('URL 需要以 http:// 或 https:// 开头');
            const author = document.getElementById('themeImpAuthor').value;
            const func = document.getElementById('themeImpFunc').value;
            for (const file of files) {
                const kind = getAssetKind(file.name);
                if (!kind) continue;
                const section = kind === 'font' ? 'font' : 'theme';
                const id = `${Date.now()}_${Math.random().toString(36).slice(2)}`;
                let content = kind !== 'font' ? await readTextFile(file) : '';
                await themeDBAction('put', id, { id, blob: file, name: file.name, type: file.type });
                db.themeAssets.push({ id, name: file.name, section, author, func, kind, content });
            }
            if (url) await importFontUrl(url, author, func);
            saveDB(); closeModal('themeImportModal'); renderMain(); alert('导入完成');
        }

        const _otp = openThemePreview;
        openThemePreview = async function (id) {
            const a = db.themeAssets.find(x => x.id === id);
            const isFont = a && a.kind === 'font';
            document.getElementById('themePreviewArea').style.display = isFont ? 'none' : '';
            if (!(isFont && a.url)) return _otp(id);
            const $ = i => document.getElementById(i);
            currentThemeAssetId = id; themeEditorVisible = false;
            $('themeEditName').value = a.name; $('themeTextEditor').classList.add('hidden'); $('themeEditToggle').classList.add('hidden');
            $('themePreviewTitle').innerText = a.name; $('themePreviewHint').innerText = '';
            $('fontSampleArea').classList.remove('hidden');
            if (currentFontFace) { try { document.fonts.delete(currentFontFace); } catch (e) {} currentFontFace = null; }
            const fam = `Font_${id}`;
            try {
                currentFontFace = new FontFace(fam, `url("${a.url}")`);
                await currentFontFace.load(); document.fonts.add(currentFontFace);
                $('fontPreview').style.fontFamily = `"${fam}"`;
            } catch (e) { $('themePreviewHint').innerText = '字体加载失败：这个链接可能不允许跨域，换个直链试试'; }
            openModal('themePreviewModal');
        };
        const _dta = downloadThemeAsset;
        downloadThemeAsset = async function () {
            const a = db.themeAssets.find(x => x.id === currentThemeAssetId);
            if (a && a.url) return window.open(a.url, '_blank');
            return _dta();
        };

        /* ===== 补丁：增加更多浏览器按钮 ===== */
        // 想增减浏览器，改这一行就行：['按钮名字', '安卓包名']
        const EXTRA_BROWSERS = [
            ['🅇 X浏览器', 'com.mmbox.xbrowser'], ['🌧️ 雨见', 'com.yujian.ResideMenuDemo'], ['🔭 可拓', 'com.yjllq.kito'],
            ['🦊 火狐', 'org.mozilla.firefox'], ['🐎 UC', 'com.UCMobile'], ['🛡️ 360', 'com.qihoo.browser'],
            ['🦁 Brave', 'com.brave.browser'], ['📱 三星', 'com.sec.android.app.sbrowser'], ['🍊 小米', 'com.android.browser']
        ];
        function buildPkgIntent(rawUrl, pkg) {
            try {
                const t = new URL(rawUrl);
                if (t.protocol !== 'http:' && t.protocol !== 'https:') return rawUrl;
                return `intent://${t.host}${t.pathname}${t.search}#Intent;scheme=${t.protocol.slice(0, -1)};package=${pkg};S.browser_fallback_url=${encodeURIComponent(t.href)};end`;
            } catch (e) { return rawUrl; }
        }
        const _olm = openLaunchModal;
        openLaunchModal = function (type, id) {
            _olm(type, id);
            const item = (type === 'phone' ? db.phones : db.apis).find(x => x.id === id);
            if (!item) return;
            const box = document.getElementById('browserButtons');
            const copyBtn = box.lastElementChild;
            EXTRA_BROWSERS.forEach(([name, pkg]) => {
                const a = document.createElement('a');
                a.className = 'browser-btn'; a.textContent = name; a.href = buildPkgIntent(item.url, pkg);
                box.insertBefore(a, copyBtn);
            });
        };

        /* ===== 补丁：强制全屏 + 小手机内嵌全屏打开 ===== */
        document.querySelector('meta[name="viewport"]').content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';
        document.head.appendChild(Object.assign(document.createElement('style'), {
            textContent: 'html{background:var(--bg-cream)}@media (display-mode: fullscreen){body{padding-top:max(12px,env(safe-area-inset-top))}}'
        }));
        function goFullscreen() {
            if (document.fullscreenElement || document.webkitFullscreenElement) return;
            const d = document.documentElement;
            const f = d.requestFullscreen || d.webkitRequestFullscreen;
            if (!f) return;
            try { const p = f.call(d, { navigationUI: 'hide' }); if (p && p.catch) p.catch(() => {}); } catch (e) {}
        }
        // 只要不在全屏，点一下屏幕就自动回到全屏
        ['click', 'touchend'].forEach(t => document.addEventListener(t, goFullscreen, { passive: true }));

        // 小手机：在 HappyHome 里全屏内嵌打开
        function openInFrame(url) {
            let m = document.getElementById('fsFrame');
            if (!m) {
                m = document.createElement('div');
                m.id = 'fsFrame'; m.className = 'modal-overlay';
                m.style.cssText = 'padding:0;background:#000;z-index:250;';
                m.innerHTML = '<div id="fsFrameBox" style="position:absolute;inset:0"></div>' +
                    '<div style="position:absolute;z-index:2;right:8px;top:calc(env(safe-area-inset-top,0px) + 8px);display:flex;gap:6px">' +
                    '<button id="fsOut" style="width:34px;height:34px;border-radius:50%;border:none;background:rgba(0,0,0,.45);color:#fff">↗</button>' +
                    '<button id="fsClose" style="width:34px;height:34px;border-radius:50%;border:none;background:rgba(0,0,0,.45);color:#fff">✕</button></div>';
                document.body.appendChild(m);
                m.querySelector('#fsClose').onclick = () => closeModal('fsFrame');
            }
            m.querySelector('#fsOut').onclick = () => window.open(url, '_blank');
            document.getElementById('fsFrameBox').innerHTML = `<iframe src="${escapeHTML(url)}" style="width:100%;height:100%;border:0;background:#fff" allow="fullscreen; autoplay; camera; microphone; clipboard-read; clipboard-write" allowfullscreen></iframe>`;
            closeModal('launchModal'); openModal('fsFrame'); goFullscreen();
        }
        const _cm = closeModal;
        closeModal = function (id) { _cm(id); if (id === 'fsFrame') document.getElementById('fsFrameBox').innerHTML = ''; };
        const _olm2 = openLaunchModal;
        openLaunchModal = function (type, id) {
            _olm2(type, id);
            const item = (type === 'phone' ? db.phones : db.apis).find(x => x.id === id);
            if (!item) return;
            const b = document.createElement('button');
            b.className = 'browser-btn'; b.style.cssText = 'grid-column:span 2;background:var(--accent-yellow);';
            b.textContent = '🖥️ 全屏打开（内嵌）';
            b.onclick = () => openInFrame(item.url);
            const box = document.getElementById('browserButtons');
            box.insertBefore(b, box.firstChild);
        };

        /* ===== 补丁：整体更顺滑、更好看（参考「小心机」的首页风格） ===== */
        (function () {
            const css = `
html{scroll-behavior:smooth}
.tab-btn,.cat-chip,.square-card,.file-card,.btn-primary,.browser-btn,.phone-type-btn{transition:transform .18s cubic-bezier(.2,.8,.2,1),box-shadow .2s,background .2s,border-color .2s}
.square-card{box-shadow:0 6px 16px rgba(92,75,59,.07)}
.square-card:active,.file-card:active,.btn-primary:active,.browser-btn:active{transform:scale(.96)}
.file-card{content-visibility:auto;contain-intrinsic-size:auto 60px}
.tab-btn.active{background:linear-gradient(135deg,var(--accent-yellow),#ffc46b);box-shadow:0 4px 12px rgba(244,162,97,.35)}
.search-box input:focus,.form-group input:focus,.form-group select:focus,.form-group textarea:focus{border-color:var(--accent-caramel);box-shadow:0 0 0 3px rgba(244,162,97,.18);outline:none}
.hh-fade{animation:hhIn .28s ease both}
@keyframes hhIn{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
.modal-overlay{display:flex!important;visibility:hidden;opacity:0;transition:opacity .22s,visibility .22s}
.modal-overlay.show{visibility:visible;opacity:1}
.modal-box{transform:translateY(20px) scale(.97);transition:transform .3s cubic-bezier(.2,.9,.3,1.05)}
.modal-overlay.show .modal-box{transform:none}
@media(max-width:600px){.modal-overlay{align-items:flex-end;padding:0}.modal-box{max-width:none;border-radius:24px 24px 0 0;max-height:88vh;padding-bottom:calc(16px + env(safe-area-inset-bottom));transform:translateY(100%)}.modal-overlay.show .modal-box{transform:none}}
header{position:relative}
.hh-night{position:absolute;right:2px;top:6px;border:1px solid var(--border-soft);background:var(--card-cream);border-radius:50%;width:34px;height:34px;font-size:1rem;cursor:pointer}
.hh-hero{background:linear-gradient(135deg,#FFE9A8,#FFCAD4);border-radius:22px;padding:14px 16px;margin-bottom:12px;box-shadow:0 8px 22px rgba(244,162,97,.22);color:#4A3828}
.hh-meta{display:flex;justify-content:space-between;font-size:.72rem;opacity:.75;letter-spacing:.04em}
.hh-hero h2{margin:2px 0;font-size:2.3rem;font-weight:800;letter-spacing:.02em}
#hhNote{margin:0 0 8px;font-size:.85rem;min-height:1.2em;outline:none;user-select:text;-webkit-user-select:text}
#hhNote:empty::before{content:attr(data-ph);opacity:.5}
.hh-stats{display:flex;gap:6px;flex-wrap:wrap}
.hh-stats span{background:rgba(255,255,255,.55);border-radius:10px;padding:2px 8px;font-size:.72rem;font-weight:700}
#coverPage{background:radial-gradient(circle at 20% 15%,#FFE9A8 0,transparent 45%),radial-gradient(circle at 85% 80%,#FFCAD4 0,transparent 50%),var(--bg-cream);background-size:140% 140%;animation:hhAur 10s ease-in-out infinite alternate}
@keyframes hhAur{to{background-position:100% 100%}}
.cover-btn:active{transform:scale(.95)}
html.night{--bg-cream:#1b1d21;--card-cream:#26292e;--accent-yellow:#d9ae55;--accent-pink:#5b3a45;--text-brown:#ece6dc;--text-light:#9a958c;--border-soft:#363a41}

html.night .square-card,html.night .file-card,html.night .tab-btn:not(.active),html.night .cat-chip:not(.active),html.night .phone-type-btn:not(.active),html.night .search-box input,html.night .form-group input,html.night .form-group select,html.night .form-group textarea,html.night .browser-btn,html.night .phone-icon-picker,html.night .asset-preview,html.night .font-preview,html.night .batch-btn-sel{background:#2a2d33!important;color:var(--text-brown)!important}
html.night .selected{background:#4a4126!important}
html.night .hh-hero{background:linear-gradient(135deg,#4a3d22,#4a2f39);color:#ece6dc}
html.night .hh-stats span{background:rgba(255,255,255,.12)}
html.night #coverPage{background:#1b1d21;animation:none}
`;
            document.head.appendChild(Object.assign(document.createElement('style'), { textContent: css }));

            // 首页卡片：时间 + 一句话状态 + 数量统计
            const hero = document.createElement('section');
            hero.id = 'hhHero'; hero.className = 'hh-hero';
            hero.innerHTML = '<div class="hh-meta"><span id="hhDate"></span><span id="hhGreet"></span></div><h2 id="hhTime">00:00</h2><p id="hhNote" contenteditable="true" spellcheck="false" data-ph="写一句今天的状态"></p><div class="hh-stats" id="hhStats"></div>';
            document.querySelector('.search-box').before(hero);
            const note = document.getElementById('hhNote');
            note.textContent = db.note === undefined ? '天天开心，万事顺意。' : db.note;
            let nt; note.addEventListener('input', () => { clearTimeout(nt); nt = setTimeout(() => { db.note = note.textContent; saveDB(); }, 400); });
            function tick() {
                const d = new Date(), p = n => String(n).padStart(2, '0'), h = d.getHours();
                document.getElementById('hhTime').textContent = p(h) + ':' + p(d.getMinutes());
                document.getElementById('hhDate').textContent = `${d.getMonth() + 1}月${d.getDate()}日 周${'日一二三四五六'[d.getDay()]}`;
                document.getElementById('hhGreet').textContent = h < 6 ? '夜深了' : h < 11 ? '早上好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好';
            }
            tick(); setInterval(tick, 20000);
            function stats() {
                const n = s => db.files.filter(f => f.section === s).length, t = s => db.themeAssets.filter(a => a.section === s).length;
                document.getElementById('hhStats').innerHTML = [['📱', db.phones.length], ['🔌', db.apis.length], ['📖', n('worldbook')], ['👤', n('character')], ['🎀', t('theme')], ['🔤', t('font')]].map(([i, c]) => `<span>${i} ${c}</span>`).join('');
            }
            const _rm3 = renderMain;
            renderMain = function () { _rm3(); stats(); hero.style.display = currentTab === 'phone' ? '' : 'none'; };

            // 切换标签：淡入 + 自动滚到当前标签
            const _st3 = switchTab;
            switchTab = function (tab, btn) {
                _st3(tab, btn);
                const c = document.getElementById('mainContent');
                c.classList.remove('hh-fade'); void c.offsetWidth; c.classList.add('hh-fade');
                if (btn && btn.scrollIntoView) btn.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
            };

            // 夜间模式
            const nb = document.createElement('button');
            nb.className = 'hh-night'; document.querySelector('header').appendChild(nb);
            let night = false; try { night = localStorage.getItem('hh_night') === '1'; } catch (e) {}
            function applyNight() {
                document.documentElement.classList.toggle('night', night);
                nb.textContent = night ? '☀️' : '🌙';
                const m = document.querySelector('meta[name="theme-color"]'); if (m) m.content = night ? '#1b1d21' : '#FFFDF7';
            }
            applyNight();
            nb.onclick = () => { night = !night; try { localStorage.setItem('hh_night', night ? '1' : '0'); } catch (e) {} applyNight(); };

            // 提示改成轻提示（不再弹框打断）
            const _alert = window.alert.bind(window);
            window.alert = m => (typeof toast === 'function' ? toast(String(m)) : _alert(m));
        })();

        /* ===== 补丁：长按菜单(编辑/多选)、取消强制全屏、状态语全页显示 ===== */
        // 1) 取消自动全屏（不再弹系统的"如需退出全屏"提示）
        ['click', 'touchend'].forEach(t => document.removeEventListener(t, goFullscreen));
        goFullscreen = function () {};
        enterApp = async function () { document.getElementById('coverPage').classList.add('hidden'); };
        // 浏览器里想全屏时，点左上角 ⛶ 手动切换（APK 里由外壳负责全屏，不显示）
        if (!/; wv\)/.test(navigator.userAgent)) {
            const fb = document.createElement('button');
            fb.className = 'hh-night'; fb.style.cssText = 'right:auto;left:2px'; fb.textContent = '⛶';
            fb.onclick = () => {
                const d = document.documentElement;
                if (document.fullscreenElement) document.exitFullscreen();
                else if (d.requestFullscreen) d.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
            };
            document.querySelector('header').appendChild(fb);
        }

        // 2) 状态语卡片所有页面都显示（非小手机页缩小）
        document.head.appendChild(Object.assign(document.createElement('style'), {
            textContent: '.hh-hero.hh-compact{padding:10px 14px}.hh-compact h2{font-size:1.5rem}.hh-compact .hh-stats{display:none}'
        }));
        const _rm4 = renderMain;
        renderMain = function () {
            _rm4();
            const h = document.getElementById('hhHero');
            if (h) { h.style.display = ''; h.classList.toggle('hh-compact', currentTab !== 'phone'); }
        };

        // 3) 小手机/API 长按：弹出 编辑 / 多选
        let editing = null;
        function showCardMenu(it) {
            let m = document.getElementById('cardMenu');
            if (!m) {
                m = document.createElement('div'); m.id = 'cardMenu'; m.className = 'modal-overlay';
                m.innerHTML = '<div class="modal-box" style="max-width:320px"><div class="modal-header"><span id="cmTitle"></span><button class="close-btn" onclick="closeModal(\'cardMenu\')">✕</button></div>' +
                    '<button class="btn-primary" id="cmEdit">✏️ 编辑</button><button class="browser-btn" id="cmSel" style="width:100%;margin-top:8px">☑️ 多选 / 全选</button></div>';
                document.body.appendChild(m);
                m.addEventListener('click', e => { if (e.target === m) closeModal('cardMenu'); });
            }
            document.getElementById('cmTitle').textContent = it.name;
            document.getElementById('cmEdit').onclick = () => { closeModal('cardMenu'); openEdit(currentTab === 'phone' ? 'phone' : 'api', it.id); };
            document.getElementById('cmSel').onclick = () => { closeModal('cardMenu'); startBatch(it.id); };
            openModal('cardMenu');
        }
        function renderGrid(container, items) {
            const isPhone = currentTab === 'phone';
            const grid = el('div', 'grid-container');
            if (!isBatchMode) {
                const add = el('div', 'square-card add-card', '<div class="plus">＋</div><span>添加</span>');
                add.addEventListener('click', () => openModal(isPhone ? 'phoneModal' : 'apiModal'));
                grid.appendChild(add);
            }
            items.forEach(it => {
                const card = el('div', 'square-card' + (selectedIds.has(it.id) ? ' selected' : ''));
                const badge = isPhone ? (it.pwd ? '<span class="badge-pwd">🔑</span>' : '') : (it.key ? '<span class="badge-pwd">🗝️</span>' : '');
                card.innerHTML = `${badge}<span class="sel-mark">✓</span><div class="square-icon">${resolveIcon(it, isPhone ? '📱' : '🔌')}</div><div class="square-title">${escapeHTML(it.name)}</div>`;
                bindLongPress(card, () => { if (isBatchMode) startBatch(it.id); else showCardMenu(it); }, () => {
                    if (isBatchMode) toggleSelectId(it.id); else openLaunchModal(isPhone ? 'phone' : 'api', it.id);
                });
                grid.appendChild(card);
            });
            container.appendChild(grid);
        }
        function openEdit(type, id) {
            const it = (type === 'phone' ? db.phones : db.apis).find(x => x.id === id);
            if (!it) return;
            const $ = i => document.getElementById(i);
            editing = { type, id };
            if (type === 'phone') {
                openModal('phoneModal');
                $('pType').value = it.type || 'free'; $('pName').value = it.name; $('pUrl').value = it.url;
                $('pEntry').value = it.entry || ''; $('pPwd').value = it.pwd || '';
                if ((it.icon || '').startsWith('data:')) { phoneIconData = it.icon; $('pIcon').value = ''; $('pIconPreview').innerHTML = `<img src="${it.icon}" alt="">`; }
                else { $('pIcon').value = it.icon || ''; previewPhoneIconUrl(); }
                document.querySelector('#phoneModal .modal-header span').textContent = '✏️ 编辑小手机';
                document.querySelector('#phoneModal .btn-primary').textContent = '保存修改';
            } else {
                openModal('apiModal');
                $('aCat').value = it.cat || db.apiCats[0];
                $('aName').value = it.name; $('aUrl').value = it.url; $('aEmail').value = it.email || '';
                $('aPwd').value = it.pwd || ''; $('aBaseUrl').value = it.baseUrl || ''; $('aKey').value = it.key || '';
                document.querySelector('#apiModal .modal-header span').textContent = '✏️ 编辑 API 站子';
                document.querySelector('#apiModal .btn-primary').textContent = '保存修改';
            }
        }
        const _sp = savePhone;
        savePhone = function () {
            if (!editing || editing.type !== 'phone') return _sp();
            const $ = i => document.getElementById(i);
            const it = db.phones.find(x => x.id === editing.id);
            const name = $('pName').value.trim(); let url = $('pUrl').value.trim();
            if (!it || !name || !url) return alert('请填写名称和链接');
            if (!url.startsWith('http')) url = 'https://' + url;
            Object.assign(it, { name, url, icon: phoneIconData || $('pIcon').value.trim(), entry: $('pEntry').value, pwd: $('pPwd').value.trim(), type: $('pType').value || 'free' });
            saveDB(); closeModal('phoneModal'); renderMain(); alert('已保存修改');
        };
        const _sa3 = saveApi;
        saveApi = function () {
            if (!editing || editing.type !== 'api') return _sa3();
            const $ = i => document.getElementById(i);
            const it = db.apis.find(x => x.id === editing.id);
            const name = $('aName').value.trim(); let url = $('aUrl').value.trim();
            if (!it || !name || !url) return alert('请填写名称和链接');
            if (!url.startsWith('http')) url = 'https://' + url;
            Object.assign(it, { name, url, cat: $('aCat').value, email: $('aEmail').value.trim(), pwd: $('aPwd').value.trim(), baseUrl: $('aBaseUrl').value.trim(), key: $('aKey').value.trim() });
saveDB(); closeModal('apiModal'); renderMain(); alert('已保存修改');
        };
        // 关闭弹窗时退出编辑状态并清空表单
        const _cm2 = closeModal;
        closeModal = function (id) {
            _cm2(id);
            const $ = i => document.getElementById(i);
            if (id === 'phoneModal') {
                editing = null; phoneIconData = '';
                ['pName', 'pUrl', 'pIcon', 'pPwd', 'pEntry'].forEach(k => $(k).value = '');
                $('pIconPreview').textContent = '＋';
                document.querySelector('#phoneModal .modal-header span').textContent = '📱 添加小手机';
                document.querySelector('#phoneModal .btn-primary').textContent = '保存到 HappyHome';
            } else if (id === 'apiModal') {
                editing = null;
                ['aName', 'aUrl', 'aEmail', 'aPwd', 'aBaseUrl', 'aKey'].forEach(k => $(k).value = '');
                document.querySelector('#apiModal .modal-header span').textContent = '🔌 添加 API 站子';
                document.querySelector('#apiModal .btn-primary').textContent = '保存 API 站子';
            }
        };

        /* ===== 补丁：恢复自动全屏 + 隐藏状态语卡片 ===== */
        document.head.appendChild(Object.assign(document.createElement('style'), { textContent: '#hhHero{display:none!important}' }));
        let hhFsPending = false;
        goFullscreen = function () {
            if (/; wv\)/.test(navigator.userAgent)) return; // APK 里由外壳负责全屏
            if (document.fullscreenElement || document.webkitFullscreenElement) return;
            const d = document.documentElement, f = d.requestFullscreen || d.webkitRequestFullscreen;
            if (!f) return;
            try { const p = f.call(d, { navigationUI: 'hide' }); if (p && p.catch) p.catch(() => {}); } catch (e) {}
        };
        // 点"点击进入"时自动全屏
        enterApp = async function () { document.getElementById('coverPage').classList.add('hidden'); goFullscreen(); };
        // 切走再回来时，下一次点击自动恢复全屏（你自己手动退出全屏时不会强拉回去）
        document.addEventListener('visibilitychange', () => { if (document.hidden) hhFsPending = true; });
        ['click', 'touchend'].forEach(t => document.addEventListener(t, () => { if (hhFsPending) { hhFsPending = false; goFullscreen(); } }, { passive: true }));

        renderMain();
          
