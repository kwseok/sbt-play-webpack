const path = require('path');
const srcDir = path.join(__dirname, '/app/assets');
const outDir = path.join(__dirname, '/target/web/webpack');

console.log('NODE_ENV  : ', process.env.NODE_ENV);
console.log('NODE_PATH : ', process.env.NODE_PATH);

module.exports = {
  entry: path.join(srcDir, 'javascripts/entry.js'),
  output: {
    path: outDir,
    filename: 'javascripts/bundle.js'
  }
};