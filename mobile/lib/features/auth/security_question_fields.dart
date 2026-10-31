import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'auth_repository.dart';

/// Question picker plus answer field, for registration and Settings (SPEC-v2
/// §7.6). Must sit inside a [Form]; [onQuestionChanged] reports the choice.
class SecurityQuestionFields extends ConsumerWidget {
  const SecurityQuestionFields({
    super.key,
    required this.questionId,
    required this.onQuestionChanged,
    required this.answer,
    this.answerError,
    this.textInputAction = TextInputAction.next,
    this.onAnswerSubmitted,
  });

  final int? questionId;
  final ValueChanged<int?> onQuestionChanged;
  final TextEditingController answer;
  final String? answerError;
  final TextInputAction textInputAction;
  final ValueChanged<String>? onAnswerSubmitted;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final questions = ref.watch(securityQuestionsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        questions.when(
          data: (list) => DropdownButtonFormField<int>(
            key: const ValueKey('security-question'),
            initialValue: questionId,
            isExpanded: true,
            // Long questions wrap: rows and the closed field grow with their text.
            isDense: false,
            itemHeight: null,
            decoration: const InputDecoration(
              labelText: 'Security question',
              prefixIcon: Icon(Icons.shield_outlined),
            ),
            items: [
              for (final q in list)
                DropdownMenuItem(
                  value: q.id,
                  child: Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Text(q.text)),
                ),
            ],
            selectedItemBuilder: (_) => [for (final q in list) Text(q.text)],
            onChanged: onQuestionChanged,
            validator: (v) => v == null ? 'Pick a question' : null,
          ),
          loading: () => const InputDecorator(
            decoration: InputDecoration(labelText: 'Security question', prefixIcon: Icon(Icons.shield_outlined)),
            child: Text('Loading questions…'),
          ),
          error: (_, _) => InputDecorator(
            decoration: const InputDecoration(
              labelText: 'Security question',
              prefixIcon: Icon(Icons.shield_outlined),
              errorText: "Couldn't load the questions",
            ),
            child: Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: () => ref.invalidate(securityQuestionsProvider),
                child: const Text('Try again'),
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
        TextFormField(
          key: const ValueKey('security-answer'),
          controller: answer,
          decoration: InputDecoration(
            labelText: 'Answer',
            helperText: 'Used to reset your password. Pick something only you know.',
            helperMaxLines: 2,
            prefixIcon: const Icon(Icons.key_outlined),
            errorText: answerError,
          ),
          // Visible while typing, so typos are caught now rather than at reset time.
          autocorrect: false,
          enableSuggestions: false,
          textInputAction: textInputAction,
          onFieldSubmitted: onAnswerSubmitted,
          validator: SecurityAnswerValidator.validate,
        ),
      ],
    );
  }
}

/// Mirrors the backend: case, spaces and punctuation don't count (SPEC-v2 §7.4).
abstract final class SecurityAnswerValidator {
  static final _ignored = RegExp(r'[\p{P}\p{S}\s]', unicode: true);

  static String? validate(String? value) {
    final letters = (value ?? '').replaceAll(_ignored, '');
    if (letters.length < 3) return 'Use at least 3 letters or digits';
    if ((value ?? '').length > 100) return 'Use at most 100 characters';
    return null;
  }
}
