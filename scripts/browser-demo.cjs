const { mkdir } = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.HUNTASSIST_PLAYWRIGHT || 'playwright');

const root = path.resolve(__dirname, '..');
const base = new URL(process.env.HUNTASSIST_BROWSER_URL || 'http://127.0.0.1:8080');
const output = path.resolve(process.env.HUNTASSIST_DEMO_DIR || path.join(root, 'target/browser-demo'));
const recording = process.argv.includes('--record');
const projectName = 'Sample Store · defensive review';

async function run() {
    assert.equal(base.protocol, 'http:');
    assert.equal(base.hostname, '127.0.0.1');
    if (recording) await mkdir(output, { recursive: true });

    const browser = await chromium.launch({ executablePath: process.env.HUNTASSIST_BROWSER });
    const context = await browser.newContext({
        viewport: { width: 1440, height: 920 },
        ...(recording ? { recordVideo: { dir: output, size: { width: 1440, height: 920 } } } : {})
    });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    const started = Date.now();
    const chapters = [];
    let caption;

    async function showCaption() {
        if (!recording) return;
        await page.evaluate(({ title, detail }) => {
            document.getElementById('demo-caption')?.remove();
            const box = document.createElement('div');
            box.id = 'demo-caption';
            Object.assign(box.style, {
                position: 'fixed', left: '50%', bottom: '22px', transform: 'translateX(-50%)',
                color: '#edf4f3', background: '#0e1719', border: '1px solid #71988d',
                boxShadow: '0 4px 18px #000a', borderRadius: '8px', padding: '10px 16px',
                maxWidth: '900px', width: 'max-content', zIndex: '2000', pointerEvents: 'none'
            });
            const heading = document.createElement('strong');
            heading.textContent = title;
            heading.style.color = '#a4e5cd';
            const explanation = document.createElement('div');
            explanation.textContent = detail;
            box.append(heading, explanation);
            document.body.append(box);
        }, caption);
    }

    async function chapter(id, name, detail) {
        chapters.push({ id, title: name, seconds: Math.floor((Date.now() - started) / 1000) });
        caption = { title: `${id} · ${name}`, detail };
        await showCaption();
        if (recording) await page.waitForTimeout(1400);
    }

    async function result(detail) {
        if (detail) caption.detail = detail;
        await showCaption();
        if (recording) await page.waitForTimeout(2500);
    }

    async function heading(name) {
        await page.getByRole('heading', { name, exact: true }).waitFor();
    }

    async function notice(expected) {
        await page.getByRole('status').filter({ hasText: expected }).waitFor();
    }

    async function clickWorkspaceLink(name, expectedHeading = name) {
        await page.getByRole('navigation', { name: 'Workspace', exact: true })
            .getByRole('link', { name, exact: true }).click();
        await heading(expectedHeading);
    }

    async function capture(filename, profile) {
        await page.getByLabel('Burp XML or HAR file').setInputFiles(path.join(root, 'samples', 'traffic', filename));
        await page.getByLabel(/Capture profile/).fill(profile);
        await page.getByRole('button', { name: 'Import traffic', exact: true }).click();
    }

    try {
        const home = await page.goto(base.href);
        assert.equal(home.status(), 200);
        assert.match(home.headers()['content-security-policy'], /frame-ancestors 'none'/);
        assert.doesNotMatch(await home.text(), /;jsessionid=/i);
        const missingToken = await context.request.post(new URL('/projects', base).href, {
            form: { name: 'Rejected', includes: 'shop.example.test' }, maxRedirects: 0
        });
        assert.equal(missingToken.status(), 403);
        const remoteHost = await context.request.get(base.href, { headers: { Host: 'external.example.test' } });
        assert.equal(remoteHost.status(), 403);

        await chapter('F01', 'Workspace and scope', 'Set an allowed host and an explicit exclusion before importing evidence.');
        await page.getByLabel('Workspace name').fill(projectName);
        await page.getByLabel(/Included hosts/).fill('shop.example.test');
        await page.getByLabel(/Excluded hosts/).fill('external.example.test');
        await page.getByLabel(/Program restrictions/).fill('Synthetic data only. Review offline.');
        await page.getByRole('button', { name: 'Create workspace', exact: true }).click();
        await heading(projectName);
        await notice('Workspace created.');
        await page.getByLabel('Host', { exact: true }).fill('external.example.test');
        await page.getByRole('button', { name: 'Check scope', exact: true }).click();
        await notice('Out of scope');
        await result('The workspace persists the rules and reports that the excluded host is out of scope.');

        await clickWorkspaceLink('Traffic imports');
        await chapter('F02', 'Traffic import and normalization', 'Import synthetic Burp XML under guest and HAR under member.');
        await capture('guest.xml', 'guest');
        await notice('Import saved.');
        await capture('member.json', 'member');
        await notice('Import saved.');
        await capture('guest.xml', 'guest');
        await notice('This capture is already imported.');
        assert.equal(await page.getByRole('row').count(), 3);
        await result('History shows retained, excluded and invalid counts. A duplicate import keeps the existing batch.');

        await clickWorkspaceLink('Endpoint inventory');
        await chapter('F03', 'Endpoint inventory', 'Filter captured methods, hosts and paths, then open an endpoint.');
        await page.getByLabel(/Filter by method/).fill('catalog');
        await page.getByRole('button', { name: 'Filter endpoints', exact: true }).click();
        await page.getByRole('link', { name: '/catalog', exact: true }).click();
        await heading('Recorded observations');
        const reviewEndpointLink = await page.getByRole('link', { name: 'Review endpoint', exact: true })
            .getAttribute('href');
        const endpoint = new URL(reviewEndpointLink, base).searchParams.get('endpoint');
        assert.ok(endpoint);
        await result('Observations show profile, status, content type and recorded header presence for the selected endpoint.');

        await chapter('F10', 'Privacy and evidence provenance', 'Open a capture reference and verify that sensitive sample values are absent.');
        const fullInventory = new URL(page.url());
        fullInventory.searchParams.delete('q');
        await page.goto(fullInventory.href);
        await page.getByText('Evidence reference', { exact: true }).first().click();
        await page.getByText('Capture entry', { exact: true }).first().waitFor();
        const shown = await page.locator('main').innerText();
        assert.doesNotMatch(shown, /fixture-session-only|fixture-cookie-only|fixtureperson/);
        await result('Each observation records its import and capture-entry reference. Query and cookie values are omitted.');

        await clickWorkspaceLink('Compare profiles');
        await chapter('F07', 'Profile comparison', 'Choose the guest and member labels to compare counts and observed status codes.');
        await page.getByLabel('First profile').selectOption('guest');
        await page.getByLabel('Second profile').selectOption('member');
        await page.getByRole('button', { name: 'Compare profiles', exact: true }).click();
        await heading('Observed differences');
        await result('Both profile columns display observed counts and status codes for the same endpoint inventory.');

        await clickWorkspaceLink('Remediation notes');
        await chapter('F04', 'Remediation notes', 'Import local Markdown and search for content security policy.');
        await page.getByLabel(/Markdown, plain text or HTML/)
            .setInputFiles(path.join(root, 'samples', 'knowledge', 'defensive-xss.md'));
        await page.getByRole('button', { name: 'Import note', exact: true }).click();
        await notice('Indexed');
        await page.getByLabel('Search terms').fill('content security policy');
        await page.getByRole('button', { name: 'Search notes', exact: true }).click();
        await heading('Search results');
        await page.getByText('Content Security Policy', { exact: true }).waitFor();
        await result('Search results cite the note source, version and matching section.');

        await page.goto(new URL(reviewEndpointLink, base).href);
        await heading('Defensive review');
        await chapter('F05', 'Modular defensive review', 'Run the XSS mitigation module against the selected captured endpoint.');
        await page.getByLabel('Review module').selectOption('xss');
        await page.getByRole('button', { name: 'Review observations', exact: true }).click();
        await heading('Observed controls');
        await result('The module maps observed controls to remediation guidance and supporting evidence.');

        await chapter('F06', 'Remediation prioritization', 'Request priorities from the applicable remediation actions.');
        await page.getByRole('button', { name: 'Prioritize remediation', exact: true }).click();
        await heading('Remediation priorities');
        await heading('Review run history');
        const planStatus = await page.getByRole('heading', { name: 'Remediation priorities', exact: true })
            .locator('xpath=..').locator('.tag').innerText();
        if (process.env.HUNTASSIST_EXPECT_MODEL_RANKED === '1') assert.equal(planStatus, 'MODEL RANKED');
        await result(planStatus === 'MODEL RANKED'
            ? 'The local model ranked a fixed list of defensive actions. The run records the model outcome.'
            : 'The result and run history record the deterministic fallback outcome.');

        await chapter('F09', 'Defensive questions', 'Ask a question about the selected endpoint’s content security policy.');
        await page.getByLabel('Ask a defensive question').fill('How should we review content security policy?');
        await page.getByRole('button', { name: 'Ask', exact: true }).click();
        await heading('Related observations');
        await result('The application selects relevant defensive guidance and links note sections.');

        await clickWorkspaceLink('Coverage');
        await chapter('F08', 'Coverage and feedback', 'Mark the catalog endpoint in review and record a decision note.');
        await page.getByLabel('Captured endpoint').selectOption(endpoint);
        await page.getByRole('combobox', { name: 'State', exact: true }).selectOption('IN_REVIEW');
        await page.getByLabel(/Decision note/).fill('Review policy with the application team.');
        await page.getByRole('button', { name: 'Save coverage', exact: true }).click();
        await notice('Coverage saved.');
        await page.getByText('Review policy with the application team.', { exact: true }).waitFor();
        await result('The coverage table shows the endpoint, module, status and stored decision.');

        if (!recording) {
            await page.setViewportSize({ width: 390, height: 844 });
            await clickWorkspaceLink('Overview', projectName);
            const fits = await page.evaluate(() => document.documentElement.scrollWidth === innerWidth);
            assert.ok(fits, 'Workspace overview fits a narrow viewport');
        }
        console.log(JSON.stringify({ verified: true, planStatus, chapters }, null, 2));
    } finally {
        const video = page.video();
        try {
            await context.close();
            if (video) await video.saveAs(path.join(output, 'browser-demo.webm'));
        } finally {
            await browser.close();
        }
    }
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
