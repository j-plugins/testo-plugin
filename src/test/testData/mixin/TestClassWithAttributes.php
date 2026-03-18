<?php

namespace Tests;

use Testo\Attribute\Test;
use Testo\Inline\TestInline;
use Testo\Bench\BenchWith;

class FeatureTest
{
    #[Test]
    public function itWorks(): void
    {
    }

    #[TestInline('inline test')]
    public function inlineTest(): void
    {
    }

    #[BenchWith(iterations: 100)]
    public function benchmarkMethod(): void
    {
    }

    public function notATest(): void
    {
    }
}
