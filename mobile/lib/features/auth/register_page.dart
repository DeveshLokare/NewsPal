import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/router.dart';
import '../../core/api_error.dart';
import 'auth_controller.dart';
import 'auth_form.dart';
import 'security_question_fields.dart';

class RegisterPage extends ConsumerStatefulWidget {
  const RegisterPage({super.key});

  @override
  ConsumerState<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends ConsumerState<RegisterPage> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  final _answer = TextEditingController();
  int? _questionId;
  bool _busy = false;
  bool _obscure = true;
  String? _error;
  String? _emailError;
  String? _answerError;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _confirm.dispose();
    _answer.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _emailError = null;
      _answerError = null;
    });
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(authControllerProvider.notifier)
          .register(_email.text.trim(), _password.text, questionId: _questionId, answer: _answer.text);
    } on ApiError catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.code == 'EMAIL_TAKEN') {
          _emailError = 'This email is already registered. Log in instead?';
        } else if (e.fieldErrors.containsKey('email')) {
          _emailError = 'Enter a valid email';
        } else if (e.fieldErrors.containsKey('answer')) {
          _answerError = 'Use at least 3 letters or digits';
        } else if (e.fieldErrors.containsKey('questionId')) {
          _error = 'Pick one of the security questions';
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
    return AuthScaffold(
      title: 'Create your account',
      subtitle: 'Pick your topics next. It takes a minute.',
      children: [
        Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _email,
                decoration: InputDecoration(
                  labelText: 'Email',
                  prefixIcon: const Icon(Icons.mail_outline),
                  errorText: _emailError,
                ),
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                textInputAction: TextInputAction.next,
                validator: AuthValidators.email,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _password,
                decoration: InputDecoration(
                  labelText: 'Password',
                  helperText: 'At least 8 characters',
                  prefixIcon: const Icon(Icons.lock_outline),
                  suffixIcon: IconButton(
                    tooltip: _obscure ? 'Show password' : 'Hide password',
                    icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                obscureText: _obscure,
                autofillHints: const [AutofillHints.newPassword],
                textInputAction: TextInputAction.next,
                validator: AuthValidators.newPassword,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _confirm,
                decoration: const InputDecoration(labelText: 'Confirm password', prefixIcon: Icon(Icons.lock_outline)),
                obscureText: _obscure,
                textInputAction: TextInputAction.next,
                validator: (v) => v != _password.text ? "Passwords don't match" : null,
              ),
              const SizedBox(height: 16),
              SecurityQuestionFields(
                questionId: _questionId,
                onQuestionChanged: (id) => setState(() => _questionId = id),
                answer: _answer,
                answerError: _answerError,
                textInputAction: TextInputAction.done,
                onAnswerSubmitted: (_) => _submit(),
              ),
              const SizedBox(height: 12),
              FormErrorText(_error),
              SubmitButton(label: 'Create account', busy: _busy, onPressed: _submit),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Wrap(
          alignment: WrapAlignment.center,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            const Text('Already have an account?'),
            TextButton(onPressed: _busy ? null : () => context.go(Routes.login), child: const Text('Log in')),
          ],
        ),
      ],
    );
  }
}
