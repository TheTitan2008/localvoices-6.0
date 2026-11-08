"""Offline threshold selection from held-out acoustic trials. No ML/server dependency.

JSON input: [{"similarity": 0.83, "margin": 0.12, "same": true}, ...]
Include unregistered speakers, near-sounding voices and multiple recording conditions.
Enrollment and trials must be disjoint recordings; calibrate EACH encoder separately.
"""
import argparse
import json


def calibrate(trials, target_far):
    positives = [t for t in trials if t['same']]
    negatives = [t for t in trials if not t['same']]
    if not positives or not negatives:
        raise ValueError('Need both genuine and impostor trials')
    for trial in trials:
        if not -1 <= trial['similarity'] <= 1 or not 0 <= trial['margin'] <= 2:
            raise ValueError('Invalid similarity or margin')
    candidates = []
    for threshold in [i / 100 for i in range(40, 101)]:
        for margin in [i / 100 for i in range(31)]:
            def accept(t):
                return t['similarity'] >= threshold and t['margin'] >= margin
            far = sum(map(accept, negatives)) / len(negatives)
            frr = 1 - sum(map(accept, positives)) / len(positives)
            if far <= target_far:
                candidates.append((frr, far, threshold, margin))
    if not candidates:
        raise ValueError('No operating point meets the requested FAR')
    frr, far, threshold, margin = min(candidates)
    return dict(threshold=threshold, margin=margin, false_accept_rate=far,
                false_reject_rate=frr, genuine_trials=len(positives), impostor_trials=len(negatives))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trials')
    parser.add_argument('--target-far', type=float, default=.01)
    args = parser.parse_args()
    if not 0 <= args.target_far <= 1:
        parser.error('--target-far must be between 0 and 1')
    with open(args.trials, encoding='utf-8-sig') as source:
        print(json.dumps(calibrate(json.load(source), args.target_far), indent=2))
