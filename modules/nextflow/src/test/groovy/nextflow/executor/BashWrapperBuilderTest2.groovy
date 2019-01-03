/*
 * Copyright 2013-2018, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.executor

import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Paths

import nextflow.processor.TaskBean
import nextflow.util.MustacheEngine
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class BashWrapperBuilderTest2 extends Specification {

    private String load(String name, Map<String,String> binding=[:]) {
        def template = new File("src/test/groovy/nextflow/executor/$name").text
        return binding ? new MustacheEngine().render(template, binding) : template
    }

    private BashWrapperBuilder MockBashWrapperBuilder(Map bean=[:]) {
        if( !bean.containsKey('workDir') )
            bean.workDir = Paths.get('/work/dir')
        new BashWrapperBuilder(bean as TaskBean)
    }

    def 'test bash wrapper' () {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * simple bash run
         */
        when:
        def bash = MockBashWrapperBuilder([
                name: 'Hello 1',
                workDir: folder,
                script: '#!/bin/bash\necho Hello world!',
                headerScript: '#BSUB -x 1\n#BSUB -y 2'
        ])
        def wrapper = bash.buildNew0()

        then:
        wrapper == load('test-bash-wrapper.txt', [folder: folder.toString()])

        cleanup:
        folder?.deleteDir()
    }

    @Unroll
    def 'test change to scratchDir' () {

        setup:
        def builder = MockBashWrapperBuilder()

        when:
        builder.scratch = SCRATCH
        then:
        builder.makeBinding().scratch_cmd == EXPECTED

        
        where:
        SCRATCH     | EXPECTED
        null        | "NXF_SCRATCH=''"
        true        | 'NXF_SCRATCH="$(set +u; nxf_mktemp $TMPDIR)"'
        '$SOME_DIR' | 'NXF_SCRATCH="$(set +u; nxf_mktemp $SOME_DIR)"'
        '/my/temp'  | 'NXF_SCRATCH="$(set +u; nxf_mktemp /my/temp)"'
        'ram-disk'  | 'NXF_SCRATCH="$(nxf_mktemp /dev/shm)"'

    }

    def 'should return task name' () {
        expect:
        MockBashWrapperBuilder(name: 'foo').makeBinding().task_name == 'foo'
        MockBashWrapperBuilder(name: 'bar').makeBinding().task_name == 'bar'
    }

    def 'should return header directives' () {
        when:
        def bash = MockBashWrapperBuilder()
        then:
        bash.makeBinding().containsKey('header_script')
        bash.makeBinding().header_script == null

        when:
        bash = MockBashWrapperBuilder(headerScript: '#BSUB -x 1\n#BSUB -y 2')
        then:
        bash.makeBinding().header_script == '#BSUB -x 1\n#BSUB -y 2'

    }

    def 'should copy control files' () {

        when:
        def binding = MockBashWrapperBuilder(scratch: false).makeBinding()
        then:
        binding.containsKey('unstage_controls')
        binding.unstage_controls == null

        when:
        binding = MockBashWrapperBuilder(scratch: true).makeBinding()
        then:
        binding.unstage_controls == '''\
                cp .command.out /work/dir/.command.out || true
                cp .command.err /work/dir/.command.err || true
                '''.stripIndent()

        when:
        binding = MockBashWrapperBuilder(scratch: true, statsEnabled: true).makeBinding()
        then:
        binding.unstage_controls == '''\
                cp .command.out /work/dir/.command.out || true
                cp .command.err /work/dir/.command.err || true
                cp .command.trace /work/dir/.command.trace || true
                '''.stripIndent()

    }

    def 'should stage inputs' () {

        given:
        def folder = Paths.get('/work/dir')
        def inputs = ['sample_1.fq':Paths.get('/some/data/sample_1.fq'), 'sample_2.fq':Paths.get('/some/data/sample_2.fq'), ]

        when:
        def binding = MockBashWrapperBuilder([
                workDir: folder,
                targetDir: folder,
                inputFiles: inputs
        ]).makeBinding()

        then:
        binding.stage_inputs == '''\
                # stage input files
                rm -f sample_1.fq
                rm -f sample_2.fq
                ln -s /some/data/sample_1.fq sample_1.fq
                ln -s /some/data/sample_2.fq sample_2.fq
                '''.stripIndent().rightTrim()


    }

    def 'should unstage outputs' () {

        given:
        def folder = Paths.get('/work/dir')
        def outputs = ['test.bam','test.bai']

        when:
        def binding = MockBashWrapperBuilder([
                workDir: folder,
                targetDir: folder,
                scratch: false,
                outputFiles: outputs
        ]).makeBinding()

        then:
        binding.containsKey('unstage_outputs')
        binding.unstage_outputs == null


        when:
        binding = MockBashWrapperBuilder([
                workDir: folder,
                targetDir: folder,
                scratch: true,
                outputFiles: outputs
        ]).makeBinding()

        then:
        binding.unstage_outputs == '''\
                mkdir -p /work/dir
                cp -fRL test.bam /work/dir || true
                cp -fRL test.bai /work/dir || true
                '''.stripIndent().rightTrim()


        when:
        binding = MockBashWrapperBuilder([
                workDir: folder,
                targetDir: Paths.get('/another/dir'),
                scratch: false,
                outputFiles: outputs
        ]).makeBinding()

        then:
        binding.unstage_outputs == '''\
                mkdir -p /another/dir
                mv -f test.bam /another/dir || true
                mv -f test.bai /another/dir || true
                '''.stripIndent().rightTrim()
    }

}
