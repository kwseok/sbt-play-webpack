/*global process, require */

(function() {
    'use strict';

    const _ = require('lodash');
    const webpack = require('webpack');
    const options = require(process.argv[2]);

    if (!_.isPlainObject(options)) {
        throw new Error('Webpack options type must be plain object');
    }

    const defaults = _.partialRight(_.mergeWith, function(obj, src) {
        if (_.isPlainObject(obj)) {
            return defaults(obj, src);
        }
        return _.isUndefined(src) ? obj : src;
    });

    defaults(options, JSON.parse(process.argv[3]));

    const outputOptions = defaults({
        colors: true,
        cached: false,
        cachedAssets: false,
        modules: true,
        chunks: false,
        reasons: false,
        errorDetails: false,
        chunkOrigins: false,
        exclude: ['node_modules', 'bower_components', 'jam', 'components']
    }, JSON.parse(process.argv[4]));

    Error.stackTraceLimit = 30;

    var lastHash = null;
    var compiler = webpack(options);

    function compilerCallback(err, stats) {
        if (!options.watch) {
            // Do not keep cache anymore
            compiler.purgeInputFileSystem();
        }
        if (err) {
            lastHash = null;
            console.error(err.stack || err);
            if (err.details) console.error(err.details);
            if (!options.watch) {
                process.on('exit', function() {
                    process.exit(1); // eslint-disable-line
                });
            }
            return
        }
        if (stats.hash !== lastHash) {
            lastHash = stats.hash;
            console.log(stats.toString(outputOptions));
        }
        if (!options.watch && stats.hasErrors()) {
            process.on('exit', function() {
                process.exit(1); // eslint-disable-line
            });
        }
    }

    if (options.watch) {
        var watchOptions = options.watchOptions || {};
        if (watchOptions.stdin) {
            process.stdin.on('end', function() {
                process.exit(0); // eslint-disable-line
            });
            process.stdin.resume();
        }
        compiler.watch(watchOptions, compilerCallback);
    } else {
        compiler.run(compilerCallback);
    }
})();
