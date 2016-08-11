const path = require('path');
const WebpackCleanupPlugin = require('webpack-cleanup-plugin');

console.log('NODE_ENV  : ', process.env.NODE_ENV);
console.log('NODE_PATH : ', process.env.NODE_PATH);

module.exports = {
    context: path.join(__dirname, '/assets'),
    entry: './javascripts/entry.js',
    output: {
        path: path.join(__dirname, '/public/bundles'),
        publicPath: '/assets/bundles/',
        filename: 'javascripts/bundle.js'
    },
    plugins: [
        new WebpackCleanupPlugin()
    ]
};
