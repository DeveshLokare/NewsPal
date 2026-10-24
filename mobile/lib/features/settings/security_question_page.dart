import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api_error.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_form.dart';
import '../auth/auth_repository.dart';
import '../auth/security_question_fields.dart';

/// Settings → Security question: set or change it (SPEC-v2 §7.6). Needs the
/// current password.
class SecurityQuestionPage extends ConsumerStatefulWidget {
  const SecurityQuestionPage({super.key});

  @override
  ConsumerState<SecurityQuestionPage> createState() => _SecurityQuestionPageState();
}

class _SecurityQuestionPageState extends ConsumerState<SecurityQuestionPage> {
  final _formKey = GlobalKey<FormState>();
  final _answer = TextEditingController();
  final _password = TextEditingController();
  int? _questionId;
  bool _busy = false;
  bool _obscure = true;
  String? _error;
  String? _passwordError;

  @override
  void dispose() {
    _answer.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _passwordError = null);
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(authRepositoryProvider).setSecurityQuestion(
            currentPassword: _password.text,
            questionId: _questionId!,
            answer: _answer.text,
          );
      ref.read(authControllerProvider.notifier).securityQuestionSaved();
      messenger.showSnackBar(const SnackBar(content: Text('Security question saved')));
      if (mounted) context.go(Routes.settings);
    } on ApiError catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.code == 'WRONG_PASSWORD') {
          _passwordError = "That password isn't right";
        } else {
          _error = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authControllerProvider).value;
    final hasQuestion = auth is SignedIn && auth.hasSecurityQuestion;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Security question')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                hasQuestion
                    ? 'Choose a new question and answer. The old one stops working.'
                    : "If you forget your password, you'll answer this to set a new one.",
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 24),
              SecurityQuestionFields(
                questionId: _questionId,
                onQuestionChanged: (id) => setState(() => _questionId = id),
                answer: _answer,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _password,
                decoration: InputDecoration(
                  labelText: 'Current password',
                  prefixIcon: const Icon(Icons.lock_outline),
                  errorText: _passwordError,
                  suffixIcon: IconButton(
                    tooltip: _obscure ? 'Show password' : 'Hide password',
                    icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                obscureText: _obscure,
                autofillHints: const [AutofillHints.password],
                textInputAction: TextInputAction.done,
                onFieldSubmitted: (_) => _save(),
                validator: (v) => AuthValidators.required(v, 'Enter your password'),
              ),
              const SizedBox(height: 12),
              FormErrorText(_error),
              SubmitButton(label: 'Save', busy: _busy, onPressed: _save),
            ],
          ),
        ),
      ),
    );
  }
}
