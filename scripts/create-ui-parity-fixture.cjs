// Rebuild the UI parity fixture with the source app's current backup encoder.
// Usage: node scripts/create-ui-parity-fixture.cjs [path-to-LexiNote-source]
const fs = require('fs');
const path = require('path');

const nativeRoot = path.resolve(__dirname, '..');
const source = path.resolve(process.argv[2] || path.join(nativeRoot, '..', 'LexiNote'));
const ts = require(path.join(source, 'node_modules/typescript'));
require.extensions['.ts'] = (module, filename) => module._compile(
  ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText,
  filename,
);

const { encodeBackup } = require(path.join(source, 'src/services/storage/backupFormat.ts'));
const at = '2026-10-26T00:00:00.000Z';
const vocabularyId = 'ui-fixture-resilient';
const sessionId = 'ui-fixture-resilient-session';
const meanings = [
  {
    id: 'ui-fixture-resilient-meaning-1',
    created_at: at,
    updated_at: at,
    part_of_speech: 'adjective',
    zh_definition: '有韧性的；能迅速恢复的',
    en_definition: 'able to recover quickly after difficulty or change',
    phonetic: '/rɪˈzɪliənt/',
    example: 'Children are often remarkably resilient.',
    source: 'UI parity fixture',
    annotations: [],
  },
  {
    id: 'ui-fixture-resilient-meaning-2',
    created_at: at,
    updated_at: at,
    part_of_speech: 'adjective',
    zh_definition: '有弹性的；恢复原状的',
    en_definition: 'returning to its original shape after being bent or stretched',
    phonetic: '/rɪˈzɪliənt/',
    example: 'Resilient materials can withstand repeated stress.',
    source: 'UI parity fixture',
    annotations: [],
  },
];
const queryResult = {
  word: 'resilient',
  type: 'word',
  phonetic: '/rɪˈzɪliənt/',
  meanings: meanings.map(({ id, part_of_speech, zh_definition, en_definition, example }) => ({
    id,
    part_of_speech,
    zh_definition,
    en_definition,
    example,
    selected: true,
    definition_choice: 'zh',
    save_zh: true,
    save_en: true,
    save_example: true,
  })),
  is_from_local: true,
  local_vocabulary_id: vocabularyId,
  follow_up_questions: ['Resilient vs resistant?'],
};
const messages = [
  {
    id: 'ui-fixture-user-message',
    role: 'user',
    content: 'What does “resilient” mean here?',
    created_at: at,
  },
  {
    id: 'ui-fixture-assistant-message',
    role: 'assistant',
    content: '“Resilient” describes someone or something that recovers quickly after difficulty. It can also describe a material that returns to its original shape.',
    created_at: at,
    query_result: queryResult,
  },
];
const historyEntry = {
  id: sessionId,
  created_at: at,
  query_type: 'text',
  input_text: 'resilient',
  query_results: queryResult,
  ai_messages: messages,
  revision: 1,
  session_state: 'ready',
  saved_content: {
    saved: true,
    vocabulary_id: vocabularyId,
    notebook_ids: ['nb_default'],
    saved_at: at,
  },
};
const backup = {
  version: '2.2',
  export_date: at,
  vocabularies: [{
    id: vocabularyId,
    type: 'word',
    content: 'resilient',
    created_at: at,
    updated_at: at,
    tags: ['character', 'recovery'],
    meanings,
  }],
  notebooks: [
    {
      id: 'nb_default',
      name: '默认笔记',
      created_at: at,
      updated_at: at,
      is_default: true,
      children_type: 'vocabulary',
      child_notebook_ids: [],
      vocabulary_ids: [vocabularyId],
      tags: ['默认'],
    },
    {
      id: 'nb_global_root',
      name: '全部词汇',
      created_at: at,
      updated_at: at,
      is_global_root: true,
      children_type: 'vocabulary',
      child_notebook_ids: [],
      vocabulary_ids: [vocabularyId],
      tags: ['全局视图'],
    },
  ],
  recent_queries: [historyEntry],
  conversations: [{
    ...historyEntry,
    metadata: {
      title: 'Resilient 的含义与用法',
      title_source: 'user',
      title_revision: 1,
      retention: 'saved',
      origin: 'query',
      created_at: at,
      last_used_at: at,
      updated_at: at,
      saved_at: at,
    },
  }],
  settings: {
    theme_mode: 'light',
    accent_color: '#10B981',
    font_size: 'medium',
    density: 'comfortable',
    default_notebook_id: 'nb_default',
    auto_record_extra_info: false,
    default_fields_to_save: {
      zh_definition: true,
      en_definition: true,
      phonetic: true,
      example: true,
      source: true,
      source_images: true,
      annotations: true,
      remarks: true,
    },
  },
};

async function main() {
  // There are intentionally no image references or credentials in this fixture.
  const archive = await encodeBackup(backup, async () => {
    throw new Error('The fixture must not contain images.');
  });
  const outDir = path.join(nativeRoot, 'docs', 'ui-parity');
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, 'fixture.json'), `${JSON.stringify(archive.backup, null, 2)}\n`);
  fs.writeFileSync(path.join(outDir, 'fixture.zip'), archive.bytes);
  console.log(`Wrote fixture.json and fixture.zip (${archive.backup.vocabularies.length} vocabulary, ${archive.backup.notebooks.length} notebooks, ${archive.backup.recent_queries.length} recent query, ${archive.backup.conversations.length} saved conversation, ${archive.imageCount} images).`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
