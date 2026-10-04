import { parentPort, workerData } from 'node:worker_threads';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { renderMermaidSVG } from 'beautiful-mermaid';
import { Resvg, initWasm } from '@resvg/resvg-wasm';

// This worker accepts diagram text, never JavaScript, HTML, or arbitrary SVG.
try {
  const source = workerData.source;
  const first = source.split('\n').map(line => line.trim()).find(line => line && !line.startsWith('%%')) ?? '';
  if (!/^(graph|flowchart|sequenceDiagram|stateDiagram(?:-v2)?|classDiagram|erDiagram|xychart-beta)\b/.test(first))
    throw new Error('This diagram type is not supported. Use flowchart, sequence, state, class, ER, or XY.');
  if (source.length > 20000 || source.split('\n').length > 300) throw new Error('Diagram exceeds 20,000 characters or 300 lines.');
  if (!/^(sequenceDiagram|erDiagram)\b/.test(first)) {
    const syntax = source.replace(/%%[^\n]*/g,'').replace(/"(?:\\.|[^"\\])*"/g,'""');
    const stack = [];
    for (const char of syntax) {
      if ('([{'.includes(char)) stack.push(char);
      else if (')]}'.includes(char) && stack.pop() !== {'}':'{',']':'[',')':'('}[char])
        throw new Error('Diagram contains unmatched brackets. Source is shown above.');
    }
    if (stack.length) throw new Error('Diagram contains unclosed brackets. Source is shown above.');
  }
  const bg = '#1a1c1e', fg = '#e7e1d5';
  const mix = percent => '#' + [0, 2, 4].map(i => Math.round(parseInt(fg.slice(1+i,3+i),16)*percent/100
    + parseInt(bg.slice(1+i,3+i),16)*(1-percent/100)).toString(16).padStart(2,'0')).join('');
  const colors = {'--fg':fg,'--bg':bg,'--_text':fg,'--_text-sec':mix(60),'--_text-muted':mix(40),
    '--_text-faint':mix(25),'--_line':mix(50),'--_arrow':mix(85),'--_node-fill':mix(3),
    '--_node-stroke':mix(20),'--_group-fill':bg,'--_group-hdr':mix(5),'--_inner-stroke':mix(12),'--_key-badge':mix(10)};
  let svg = renderMermaidSVG(source, { bg, fg, font: 'Pixel Code' });
  if (!/<text\b/.test(svg)) throw new Error('No diagram content was recognized. Check the source above.');
  // resvg has no browser CSS variables or web fonts. Supply a fixed palette and bundled font.
  svg = svg.replace(/<style>[\s\S]*?<\/style>/g, '<style>text { font-family: Pixel Code; }</style>')
    .replace(/var\((--[\w-]+)\)/g, (all, key) => colors[key] ?? all);
  // Use Pixel Code's native size; the diagram layout assumes a narrower proportional font.
  svg = svg.replace(/<text\b[^>]*>/g, tag => {
    const size = /font-size="([\d.]+)"/.exec(tag);
    if (!size) return tag;
    return tag.replace(/font-size="[\d.]+"/, 'font-size="9"')
      .replace(/dy="([\d.]+)"/, (_, dy) => `dy="${Number(dy) * 9 / Number(size[1])}"`);
  });
  if (svg.includes('var(') || svg.length > 2000000) throw new Error('Diagram is too complex to display.');
  const require = createRequire(import.meta.url);
  await initWasm(await readFile(join(dirname(require.resolve('@resvg/resvg-wasm')), 'index_bg.wasm')));
  const font = await readFile(new URL('./assets/PixelCode.ttf', import.meta.url));
  const options = { background:bg, fitTo:{mode:'width',value:1200},
    font:{fontBuffers:[font],defaultFontFamily:'Pixel Code',sansSerifFamily:'Pixel Code',monospaceFamily:'Pixel Code'} };
  let renderer = new Resvg(svg, options);
  let rendered;
  try {
    if (renderer.width * renderer.height > 4000000 || renderer.height > 4096) {
      const scale = Math.min(4096 / renderer.height, Math.sqrt(4000000 / (renderer.width * renderer.height)));
      const width = Math.max(1, Math.floor(renderer.width * scale));
      renderer.free();
      renderer = new Resvg(svg, {...options, fitTo:{mode:'width',value:width}});
    }
    rendered = renderer.render();
    const png = rendered.asPng();
    if (png.length > 5 * 1024 * 1024) throw new Error('Rendered diagram exceeds 5 MB.');
    parentPort.postMessage({base64:Buffer.from(png).toString('base64'),mimeType:'image/png'});
  } finally { rendered?.free(); renderer.free(); }
} catch (error) { parentPort.postMessage({error: error instanceof Error ? error.message : String(error)}); }
